package com.mk.androidtransfer.network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Retrofit客户端单例
 */
public class RetrofitClient {
    /** 当前电脑的配对令牌，由 setToken 设置；请求统一带上。 */
    private static volatile String token = "";

    public static void setToken(String value) {
        token = value == null ? "" : value;
    }

    public static String getToken() {
        return token;
    }

    private static RetrofitClient instance;
    private ApiService apiService;
    private OkHttpClient okHttpClient;
    private String baseUrl;

    private RetrofitClient(String baseUrl) {
        this.baseUrl = baseUrl;
        initRetrofit();
    }

    public static synchronized RetrofitClient getInstance(String baseUrl) {
        if (instance == null || !instance.baseUrl.equals(baseUrl)) {
            instance = new RetrofitClient(baseUrl);
        }
        return instance;
    }

    private void initRetrofit() {
        // 日志拦截器
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    String t = token;
                    if (t.isEmpty()) {
                        return chain.proceed(chain.request());
                    }
                    return chain.proceed(chain.request().newBuilder()
                            .header(com.mk.androidtransfer.network.Pairing.header(), t)
                            .build());
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .addInterceptor(loggingInterceptor)
                .build();

        // Retrofit实例
        this.okHttpClient = okHttpClient;

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);
    }

    public ApiService getApiService() {
        return apiService;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }
}
