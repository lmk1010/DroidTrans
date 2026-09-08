package com.mk.androidtransfer.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 与电脑的配对状态。
 *
 * <p>电脑侧默认要求配对：局域网里谁都能连的话，公共 Wi-Fi 下等于门户大开。
 * 手机拿六位配对码（扫码就带上了）换一个长期令牌，之后每个请求带 X-DT-Token。
 * 令牌按「电脑」分别保存，换一台电脑不会互相顶掉。
 */
public final class Pairing {

    private static final String PREFS = "DroidTransPairing";
    private static final String HEADER = "X-DT-Token";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build();

    /** 问「要不要配对」这种关键判断，超时给宽一点：判错的代价是把用户带到错的一屏。 */
    private static final OkHttpClient PATIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private Pairing() {
    }

    public static String header() {
        return HEADER;
    }

    private static String keyOf(String baseUrl) {
        return "token:" + normalize(baseUrl);
    }

    /** 统一成 host:port，别让末尾斜杠和 http:// 前缀把同一台电脑存成两份。 */
    public static String normalize(String baseUrl) {
        String s = baseUrl == null ? "" : baseUrl.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    @Nullable
    public static String token(Context ctx, String baseUrl) {
        return prefs(ctx).getString(keyOf(baseUrl), null);
    }

    public static void saveToken(Context ctx, String baseUrl, String token) {
        prefs(ctx).edit().putString(keyOf(baseUrl), token).apply();
    }

    public static void forget(Context ctx, String baseUrl) {
        prefs(ctx).edit().remove(keyOf(baseUrl)).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 用配对码换令牌并保存。
     *
     * @return 成功返回 true；配对码不对或连不上返回 false。
     */
    public static boolean pair(Context ctx, String baseUrl, String code) {
        String host = normalize(baseUrl);
        try {
            JSONObject body = new JSONObject();
            body.put("code", code == null ? "" : code.trim());
            body.put("device_id", deviceId(ctx));
            body.put("device_name", android.os.Build.MODEL);

            Request req = new Request.Builder()
                    .url("http://" + host + "/api/pair")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response res = CLIENT.newCall(req).execute()) {
                ResponseBody rb = res.body();
                if (!res.isSuccessful() || rb == null) {
                    return false;
                }
                JSONObject obj = new JSONObject(rb.string());
                String token = obj.optString("token", "");
                if (token.isEmpty()) {
                    return false;
                }
                saveToken(ctx, host, token);
                return true;
            }
        } catch (IOException | org.json.JSONException e) {
            return false;
        }
    }

    /**
     * 存着的令牌对面还认不认。
     *
     * <p>手机当接收端时令牌是<b>一次性</b>的：对面每次重新进入「我要收」都会
     * 把它作废。而发送端只要本地存过一个令牌就不再敲门，于是一直拿着一张
     * 过期的票去传，对面一律 403 —— 用户看到的正是「第一次能传，之后就不行了」。
     *
     * <p>拿一个需要鉴权的口探一下最准：/api/outbox 电脑和手机都实现了。
     */
    public static boolean tokenWorks(String baseUrl, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Request req = new Request.Builder()
                .url("http://" + normalize(baseUrl) + "/api/outbox")
                .header(HEADER, token)
                .get()
                .build();
        try (Response res = CLIENT.newCall(req).execute()) {
            return res.isSuccessful();
        } catch (Exception e) {
            // 连不上不等于令牌坏了，这时候别把它删掉
            return true;
        }
    }

    /** 对面的配对要求：要不要配、以及怎么配。 */
    public static final class Info {
        public final boolean required;
        public final String mode;
        /** 问到了没有。没问到时**不能**当成「要输码」，见 ConnectActivity。 */
        public final boolean known;

        Info(boolean required, String mode, boolean known) {
            this.required = required;
            this.mode = mode == null ? "" : mode;
            this.known = known;
        }
    }

    /**
     * 一次问清楚：要不要配对、以及怎么配。
     *
     * <p>原来这两件事分两次请求问，任何一次超时都会退化成「按要输码处理」，
     * 于是手机连手机时冒出一屏六位码 —— 而手机之间根本就不该有码，
     * 对面也因此从没被敲门，屏幕上什么都不弹。
     *
     * <p>所以这里问一次、失败重试一次，并且如实告诉调用方「到底问到没有」。
     */
    public static Info info(String baseUrl) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Request req = new Request.Builder()
                    .url("http://" + normalize(baseUrl) + "/api/wifi/info")
                    .get()
                    .build();
            try (Response res = PATIENT.newCall(req).execute()) {
                ResponseBody rb = res.body();
                if (!res.isSuccessful() || rb == null) {
                    continue;
                }
                JSONObject j = new JSONObject(rb.string());
                return new Info(j.optBoolean("pairing_required", true),
                        j.optString("pairing_mode", ""), true);
            } catch (Exception ignored) {
                // 再试一次；网络抖一下不该把用户推进一屏用不上的配对码
            }
        }
        return new Info(true, "", false);
    }

    /**
     * 对面怎么配对。
     *
     * <p>"approve" 表示对面是一台手机：点一下同意就行，不用输码。
     * 电脑不发这个字段，返回空串 —— 猜错的方向要选安全的那一边，
     * 也就是「还是要输码」。
     */
    public static String pairingMode(String baseUrl) {
        Request req = new Request.Builder()
                .url("http://" + normalize(baseUrl) + "/api/wifi/info")
                .get()
                .build();
        try (Response res = CLIENT.newCall(req).execute()) {
            ResponseBody rb = res.body();
            if (!res.isSuccessful() || rb == null) {
                return "";
            }
            return new JSONObject(rb.string()).optString("pairing_mode", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 发现雷达节点时读取实现引擎。Bonjour 只告诉我们地址和端口，
     * 真实的 Android 机型名（例如 PLK110）不能靠名字判断。
     */
    public static String engine(String baseUrl) {
        Request req = new Request.Builder()
                .url("http://" + normalize(baseUrl) + "/api/health")
                .get()
                .build();
        try (Response res = CLIENT.newCall(req).execute()) {
            ResponseBody rb = res.body();
            if (!res.isSuccessful() || rb == null) {
                return "";
            }
            return new JSONObject(rb.string()).optString("engine", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 敲门：不带码地请求一次，挂在那儿等对面点头。
     *
     * <p>用的还是 /api/pair，只是 code 留空 —— 对面认得这个约定，
     * 会把这条请求挂起来弹个框问它的主人。所以协议一个字节都没改。
     *
     * <p>要等人点击，超时给得比常规请求宽得多。
     */
    public static boolean knock(Context ctx, String baseUrl) {
        OkHttpClient patient = CLIENT.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        String host = normalize(baseUrl);
        try {
            JSONObject body = new JSONObject();
            body.put("code", "");
            body.put("device_id", deviceId(ctx));
            body.put("device_name", android.os.Build.MODEL);

            Request req = new Request.Builder()
                    .url("http://" + host + "/api/pair")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response res = patient.newCall(req).execute()) {
                ResponseBody rb = res.body();
                if (!res.isSuccessful() || rb == null) {
                    return false;
                }
                String token = new JSONObject(rb.string()).optString("token", "");
                if (token.isEmpty()) {
                    return false;
                }
                saveToken(ctx, host, token);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 这台电脑是否要求配对；连不上时按「不要求」处理，让原有流程照旧报错。 */
    public static boolean requiresPairing(String baseUrl) {
        Request req = new Request.Builder()
                .url("http://" + normalize(baseUrl) + "/api/wifi/info")
                .get()
                .build();
        try (Response res = CLIENT.newCall(req).execute()) {
            ResponseBody rb = res.body();
            if (!res.isSuccessful() || rb == null) {
                return false;
            }
            return new JSONObject(rb.string()).optBoolean("pairing_required", false);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("HardwareIds")
    private static String deviceId(Context ctx) {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "android" : id;
    }
}
