/*
 * (C) Copyright 2016-2017 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Vladimir Pasquier <vpasquier@nuxeo.com>
 *     Kevin Leturc <kleturc@nuxeo.com>
 */
package org.nuxeo.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.nuxeo.client.cache.NuxeoResponseCache;
import org.nuxeo.client.marshaller.NuxeoConverterFactory;
import org.nuxeo.client.objects.Connectable;
import org.nuxeo.client.objects.Document;
import org.nuxeo.client.objects.Documents;
import org.nuxeo.client.objects.EntityTypes;
import org.nuxeo.client.objects.Operation;
import org.nuxeo.client.objects.RecordSet;
import org.nuxeo.client.objects.Repository;
import org.nuxeo.client.objects.blob.Blobs;
import org.nuxeo.client.objects.blob.FileBlob;
import org.nuxeo.client.objects.config.ConfigManager;
import org.nuxeo.client.objects.directory.DirectoryManager;
import org.nuxeo.client.objects.task.TaskManager;
import org.nuxeo.client.objects.upload.BatchUploadManager;
import org.nuxeo.client.objects.user.User;
import org.nuxeo.client.objects.user.UserManager;
import org.nuxeo.client.spi.NuxeoClientException;
import org.nuxeo.client.spi.auth.BasicAuthInterceptor;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;

/**
 * @since 0.1
 */
public class NuxeoClient {

    public static final Pattern CMIS_PRODUCT_VERSION_PATTERN = Pattern.compile("\"productVersion\":\"(.*?)\"");

    protected final OkHttpClient.Builder okhttpBuilder;

    protected final Retrofit.Builder retrofitBuilder;

    protected final NuxeoConverterFactory converterFactory;

    protected final Map<String, Interceptor> headerInterceptors = new HashMap<>();

    protected NuxeoResponseCache nuxeoCache;

    protected Retrofit retrofit;

    protected User currentUser;

    protected NuxeoVersion serverVersion;

    protected NuxeoClient(Builder builder) {
        // okhttp builder
        okhttpBuilder = new OkHttpClient.Builder();
        okhttpBuilder.addInterceptor(builder.authenticationMethod);
        // retrofit builder
        converterFactory = NuxeoConverterFactory.create();
        retrofitBuilder = new Retrofit.Builder().baseUrl(builder.url + ConstantsV1.API_PATH)
                                                .addConverterFactory(converterFactory);
        nuxeoCache = builder.cache;
        if (builder.connectTimeout != null) {
            okhttpBuilder.connectTimeout(builder.connectTimeout.longValue(), TimeUnit.SECONDS);
        }
        if (builder.readTimeout != null) {
            okhttpBuilder.readTimeout(builder.readTimeout.longValue(), TimeUnit.SECONDS);
        }
        // client builder
        retrofit();
    }

    /*******************************
     * Settings *
     ******************************/

    public NuxeoClient header(String header, String value) {
        Interceptor previousInterceptor = headerInterceptors.remove(header);
        if (previousInterceptor != null) {
            okhttpBuilder.interceptors().remove(previousInterceptor);
        }
        Interceptor interceptor = chain -> {
            Request request = chain.request();
            request = request.newBuilder().addHeader(header, value).build();
            return chain.proceed(request);
        };
        headerInterceptors.put(header, interceptor);
        okhttpBuilder.interceptors().add(interceptor);
        retrofit();
        return this;
    }

    public NuxeoClient enrichers(String... enrichers) {
        header(HttpHeaders.X_ENRICHERS, StringUtils.join(enrichers, ","));
        return this;
    }

    public NuxeoClient voidOperation(boolean value) {
        header(HttpHeaders.X_VOID_OPERATION, Boolean.toString(value));
        return this;
    }

    public NuxeoClient transactionTimeout(long timeout) {
        header(HttpHeaders.NUXEO_TX_TIMEOUT, String.valueOf(timeout));
        return this;
    }

    public NuxeoClient fetch(String... fetchs) {
        header(HttpHeaders.X_FETCH, StringUtils.join(fetchs, ","));
        return this;
    }

    /**
     * Sets the depth.
     * <p />
     * Possible values are: `root`, `children` and `max`.
     * <p />
     * 
     * @see org.nuxeo.ecm.core.io.registry.context.DepthValues
     */
    public NuxeoClient depth(String value) {
        header(HttpHeaders.DEPTH, value);
        return this;
    }

    public NuxeoClient version(String value) {
        header(HttpHeaders.X_VERSIONING_OPTION, value);
        return this;
    }

    public NuxeoClient schemas(String... properties) {
        header(HttpHeaders.X_PROPERTIES, String.join(",", properties));
        return this;
    }

    /*******************************
     * Client Services *
     ******************************/

    public NuxeoConverterFactory getConverterFactory() {
        return converterFactory;
    }

    public NuxeoResponseCache getNuxeoCache() {
        return nuxeoCache;
    }

    public boolean isCacheEnabled() {
        return nuxeoCache != null;
    }

    public NuxeoClient refreshCache() {
        if (isCacheEnabled()) {
            nuxeoCache.invalidateAll();
        }
        return this;
    }

    public void disconnect() {
        okhttpBuilder.interceptors().clear();
        headerInterceptors.clear();
        nuxeoCache = null;
        retrofit();
    }

    /******************************
     * Services *
     ******************************/

    /**
     * This method returns the current logged user retrieved on {@link NuxeoClient} creation.
     *
     * @return the current logged user.
     */
    public User getCurrentUser() {
        return currentUser;
    }

    /**
     * This method gets the Nuxeo server version from CMIS the first time and then caches it.
     *
     * @return The Nuxeo server version.
     */
    public NuxeoVersion getServerVersion() {
        if (serverVersion == null) {
            try {
                // Remove API_PATH from the base url
                // Get repository capabilities on CMIS
                Response response = get(
                        retrofit.baseUrl().toString().replaceFirst(ConstantsV1.API_PATH, "") + "/json/cmis");
                String body = response.body().string();
                Matcher matcher = CMIS_PRODUCT_VERSION_PATTERN.matcher(body);
                if (matcher.find()) {
                    String version = matcher.group(1);
                    serverVersion = NuxeoVersion.parse(version);
                } else {
                    throw new NuxeoClientException("Unable to get version from CMIS");
                }
            } catch (IOException ioe) {
                throw new NuxeoClientException("Unable to retrieve the server version.", ioe);
            }
        }
        return serverVersion;
    }

    /**
     * @return A repository service linked to `default` repository in Nuxeo.
     */
    public Repository repository() {
        return new Repository(this);
    }

    public Repository repository(String repositoryName) {
        return new Repository(this, repositoryName);
    }

    public Operation automation(String operationId) {
        return new Operation(this, operationId);
    }

    public UserManager userManager() {
        return new UserManager(this);
    }

    public DirectoryManager directoryManager() {
        return new DirectoryManager(this);
    }

    public TaskManager taskManager() {
        return new TaskManager(this);
    }

    public BatchUploadManager batchUploadManager() {
        return new BatchUploadManager(this);
    }

    public ConfigManager configManager() {
        return new ConfigManager(this);
    }

    /*******************************
     * HTTP Services *
     ******************************/

    public Response get(String url) {
        return request(url, Request.Builder::get);
    }

    public Response delete(String url) {
        return request(url, Request.Builder::delete);
    }

    public Response delete(String url, String json) {
        RequestBody body = RequestBody.create(MediaTypes.APPLICATION_JSON_CHARSET_UTF_8.toOkHttpMediaType(), json);
        return request(url, builder -> builder.delete(body));
    }

    public Response put(String url, String json) {
        RequestBody body = RequestBody.create(MediaTypes.APPLICATION_JSON_CHARSET_UTF_8.toOkHttpMediaType(), json);
        return request(url, builder -> builder.put(body));
    }

    public Response post(String url, String json) {
        RequestBody body = RequestBody.create(MediaTypes.APPLICATION_JSON_CHARSET_UTF_8.toOkHttpMediaType(), json);
        return request(url, builder -> builder.post(body));
    }

    protected Response request(String url, UnaryOperator<Request.Builder> method) {
        try {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            Request request = method.apply(requestBuilder).build();
            return retrofit.callFactory().newCall(request).execute();
        } catch (IOException e) {
            throw new NuxeoClientException(e);
        }
    }

    /**
     * Re-build the retrofit context.
     */
    protected void retrofit() {
        OkHttpClient okHttpClient = okhttpBuilder.build();
        retrofit = retrofitBuilder.callFactory(okHttpClient).build();
    }

    /**
     * USE THIS METHOD WITH CAUTION
     * <p />
     * This method returns an usable API object based on current client configuration. Further parameters set on client
     * won't be used by this API object.
     *
     * @return A new API instance from retrofit,
     */
    public <A> A createApi(Class<A> apiClass) {
        return retrofit.create(apiClass);
    }

    public <T> T fetchResponse(Call<T> call) {
        if (isCacheEnabled()) {
            String cacheKey = computeCacheKey(call);
            T result = nuxeoCache.getBody(cacheKey);
            if (result != null) {
                return result;
            }
        }
        try {
            retrofit2.Response<T> response = call.execute();
            response = handleResponse(call, response);
            return response.body();
        } catch (IOException reason) {
            throw new NuxeoClientException(reason);
        }
    }

    public <T> void fetchResponse(Call<T> call, Callback<T> callback) {
        call.enqueue(new Callback<T>() {

            @Override
            public void onResponse(Call<T> call, retrofit2.Response<T> response) {
                try {
                    callback.onResponse(call, handleResponse(call, response));
                } catch (NuxeoClientException nce) {
                    callback.onFailure(call, nce);
                }
            }

            @Override
            public void onFailure(Call<T> call, Throwable t) {
                callback.onFailure(call, t);
            }

        });
    }

    @SuppressWarnings("unchecked")
    protected <T> retrofit2.Response<T> handleResponse(Call<T> call, retrofit2.Response<T> response) {
        try {
            // For redirect 308 -> the response should be success
            if (!response.isSuccessful() && response.code() != 308) {
                NuxeoClientException nuxeoClientException;
                String errorBody = response.errorBody().string();
                if (Strings.EMPTY.equals(errorBody)) {
                    nuxeoClientException = new NuxeoClientException(response.code(), response.message());
                } else {
                    MediaType mediaType = MediaType.fromOkHttpMediaType(response.raw().body().contentType());
                    if (!MediaTypes.APPLICATION_JSON.equalsTypeSubType(mediaType)
                            && !MediaTypes.APPLICATION_JSON_NXENTITY.equalsTypeSubType(mediaType)) {
                        nuxeoClientException = new NuxeoClientException(response.code(), errorBody);
                    } else {
                        nuxeoClientException = converterFactory.readJSON(errorBody, NuxeoClientException.class);
                    }
                }
                throw nuxeoClientException;
            }
            if (isCacheEnabled()) {
                nuxeoCache.put(computeCacheKey(call), response);
            }
            T body = response.body();
            Headers headers = response.headers();
            if (body instanceof ResponseBody) {
                throw new IllegalStateException("Internal client error, everything should be mapped to a type.");
            } else if (body == null) {
                if (response.code() == 204
                        && MediaTypes.APPLICATION_NUXEO_EMPTY_LIST_S.equals(headers.get("Content-Type"))) {
                    return retrofit2.Response.success((T) new Blobs(), response.raw());
                }
            } else if (body instanceof Connectable) {
                ((Connectable) body).reconnectWith(this);
            } else if (body instanceof List<?>) {
                for (Object item : (List<?>) body) {
                    if (item instanceof Connectable) {
                        ((Connectable) item).reconnectWith(this);
                    }
                }
            }
            if (body instanceof FileBlob) {
                FileBlob fbody = (FileBlob) body;

                String filename = null;
                String contentDisposition = headers.get("Content-Disposition");
                if (contentDisposition != null) {
                    filename = contentDisposition.replaceFirst(".*filename\\*?=(UTF-8'')?(.*)", "$2");
                    try {
                        filename = URLDecoder.decode(filename, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        // May not happen
                    }
                }
                if (filename == null) {
                    filename = fbody.getFilename();
                }

                String mimeType = headers.get("Content-Type");
                if (mimeType == null) {
                    mimeType = MediaTypes.APPLICATION_OCTET_STREAM_S;
                }
                FileBlob fileBlob = new FileBlob(filename, mimeType, fbody.getFile());
                return retrofit2.Response.success((T) fileBlob, response.raw());
            }
            // No need to wrap the response
            return response;
        } catch (IOException ioe) {
            throw new NuxeoClientException(ioe);
        }
    }

    /**
     * Compute the cache key with request
     */
    protected String computeCacheKey(Call<?> methodResult) {
        Request originalRequest = methodResult.request();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ConstantsV1.MD_5);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        digest.update((originalRequest.toString() + originalRequest.headers().toString()).getBytes());
        byte messageDigest[] = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte msg : messageDigest) {
            hexString.append(Integer.toHexString(0xFF & msg));
        }
        return hexString.toString();
    }

    /**
     * {@link NuxeoClient} builder.
     *
     * @since 3.0
     */
    public static class Builder {

        protected String url;

        protected Interceptor authenticationMethod;

        protected NuxeoResponseCache cache;

        protected Long connectTimeout;

        protected Long readTimeout;

        protected Map<String, Class<?>> entityTypeToClass;

        public Builder() {
            // init default values
            entityTypeToClass = new HashMap<>();
            entityTypeToClass.put(EntityTypes.DOCUMENT, Document.class);
            entityTypeToClass.put(EntityTypes.DOCUMENTS, Documents.class);
            entityTypeToClass.put(EntityTypes.RECORDSET, RecordSet.class);
            entityTypeToClass.put(EntityTypes.USER, User.class);
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder authentication(String username, String password) {
            return authentication(new BasicAuthInterceptor(username, password));
        }

        public Builder authentication(Interceptor authenticationMethod) {
            this.authenticationMethod = authenticationMethod;
            return this;
        }

        public Builder cache(NuxeoResponseCache cache) {
            this.cache = cache;
            return this;
        }

        // TODO check if it's enough to set timeouts on creation
        /**
         * Sets the given timeout to connect and read timeout settings of client. The timeout unit is seconds.
         */
        public Builder timeout(long timeout) {
            return connectTimeout(timeout).readTimeout(timeout);
        }

        /**
         * The timeout unit is seconds.
         */
        public Builder connectTimeout(long connectTimeout) {
            this.connectTimeout = Long.valueOf(connectTimeout);
            return this;
        }

        /**
         * The timeout unit is seconds.
         */
        public Builder readTimeout(long readTimeout) {
            this.readTimeout = Long.valueOf(readTimeout);
            return this;
        }

        /**
         * Register entity type to class link for automatic unmarshalling process in automation.
         * <p />
         * CAUTION: this method is just a convenient way to register entity pojo. This operation is done in a static way
         * on NuxeoConverterFactory.
         */
        public Builder registerEntity(String entityType, Class<?> clazz) {
            entityTypeToClass.put(entityType, clazz);
            return this;
        }

        /**
         * Builds a {@link NuxeoClient} and log it, it will throw a {@link NuxeoClientException} if failed.
         */
        public NuxeoClient connect() {
            // init client
            NuxeoClient client = new NuxeoClient(this);
            // register entity type class
            entityTypeToClass.forEach(NuxeoConverterFactory::registerEntity);
            // login client on server
            client.currentUser = client.userManager().fetchCurrentUser();
            return client;
        }

    }

}