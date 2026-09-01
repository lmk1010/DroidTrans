package com.mk.androidtransfer.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Network client for activation and periodic license renewal. */
public final class LicenseApi {
    public static final String PRICING_URL =
            "https://droidtrans.mkstore.life/pricing.html";
    private static final String BASE = "https://droidtrans.mkstore.life";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new Gson();

    public interface ResultCallback {
        void onSuccess(String token);
        void onFailure(ApiException error);
    }

    public static final class ApiException extends Exception {
        private final String code;

        ApiException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private LicenseApi() {
    }

    public static void activate(String code, ResultCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("code", code);
        post("/api/activate", body, callback);
    }

    public static void refresh(String token, ResultCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("license", token);
        post("/api/refresh", body, callback);
    }

    public static String normalizeCode(String input) {
        if (input == null) return "";
        String raw = input.toUpperCase(Locale.US).replaceAll("[^0-9A-Z]", "");
        String body = raw.startsWith("DT") ? raw.substring(2) : raw;
        if (body.length() != 12) return "";
        body = body.replace('O', '0').replace('I', '1').replace('L', '1');
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        for (int i = 0; i < body.length(); i++) {
            if (alphabet.indexOf(body.charAt(i)) < 0) return "";
        }
        return "DT-" + body.substring(0, 4) + "-" + body.substring(4, 8)
                + "-" + body.substring(8);
    }

    private static void post(String path, Map<String, String> body,
                             ResultCallback callback) {
        Request request = new Request.Builder()
                .url(BASE + path)
                .post(RequestBody.create(GSON.toJson(body), JSON))
                .build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure(new ApiException("network", e.getLocalizedMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closed = response) {
                    String text = closed.body() == null ? "" : closed.body().string();
                    JsonObject json = text.isEmpty()
                            ? new JsonObject()
                            : JsonParser.parseString(text).getAsJsonObject();
                    if (!closed.isSuccessful()) {
                        String code = json.has("error")
                                ? json.get("error").getAsString()
                                : "http";
                        callback.onFailure(new ApiException(code, "HTTP " + closed.code()));
                        return;
                    }
                    String token = json.has("license")
                            ? json.get("license").getAsString()
                            : "";
                    if (token.isEmpty()) {
                        callback.onFailure(new ApiException("no_license", "No license"));
                        return;
                    }
                    callback.onSuccess(token);
                } catch (Exception e) {
                    callback.onFailure(new ApiException("bad_response", e.getLocalizedMessage()));
                }
            }
        });
    }
}
