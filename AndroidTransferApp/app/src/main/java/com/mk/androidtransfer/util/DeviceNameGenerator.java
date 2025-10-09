package com.mk.androidtransfer.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * 设备名称生成器
 * 生成优雅的随机设备名，格式：形容词+名词-ID
 * 例如：优雅的天鹅-AB12
 */
public class DeviceNameGenerator {

    private static final String PREF_NAME = "device_prefs";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_DEVICE_ID = "device_id";

    // 优雅的形容词列表
    private static final String[] ADJECTIVES = {
            "优雅的", "灵动的", "精致的", "华丽的", "神秘的",
            "迷人的", "闪耀的", "静谧的", "活泼的", "温柔的",
            "勇敢的", "智慧的", "梦幻的", "纯净的", "高贵的",
            "自由的", "快乐的", "坚强的", "可爱的", "迷人的"
    };

    // 优美的名词列表
    private static final String[] NOUNS = {
            "天鹅", "海豚", "蝴蝶", "星辰", "月光",
            "彩虹", "晨曦", "薄雾", "清风", "雪花",
            "樱花", "极光", "流星", "云朵", "波浪",
            "珍珠", "翡翠", "琥珀", "水晶", "钻石"
    };

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_LENGTH = 4;

    private static Random random = new Random();

    /**
     * 获取或生成设备名称
     * 如果已存在则返回，否则生成新的并保存
     */
    public static String getOrGenerateDeviceName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedName = prefs.getString(KEY_DEVICE_NAME, null);

        if (savedName != null && !savedName.isEmpty()) {
            return savedName;
        }

        // 生成新的设备名
        String newName = generateDeviceName();
        prefs.edit().putString(KEY_DEVICE_NAME, newName).apply();
        return newName;
    }

    /**
     * 生成随机设备名称
     * 格式：形容词+名词-ID
     */
    private static String generateDeviceName() {
        String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[random.nextInt(NOUNS.length)];
        String id = generateRandomId();
        return adjective + noun + "-" + id;
    }

    /**
     * 生成随机ID
     */
    private static String generateRandomId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    /**
     * 获取设备ID（用于唯一标识）
     * 如果不存在则生成一个新的UUID风格的ID
     */
    public static String getOrGenerateDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedId = prefs.getString(KEY_DEVICE_ID, null);

        if (savedId != null && !savedId.isEmpty()) {
            return savedId;
        }

        // 生成新的设备ID
        String newId = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply();
        return newId;
    }

    /**
     * 重新生成设备名称
     */
    public static String regenerateDeviceName(Context context) {
        String newName = generateDeviceName();
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_NAME, newName).apply();
        return newName;
    }

    /**
     * 清除保存的设备信息
     */
    public static void clearDeviceInfo(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
}
