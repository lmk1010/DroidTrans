package com.mk.androidtransfer.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从上传记录 JSON 中提取成功文件的稳定标识。
 */
public final class UploadRecordFiles {

    private UploadRecordFiles() {
    }

    public static Set<String> successfulPaths(String fileList) {
        Set<String> paths = new LinkedHashSet<>();
        if (fileList == null || fileList.trim().isEmpty()) {
            return paths;
        }

        try {
            JsonElement root = JsonParser.parseString(fileList);
            if (!root.isJsonArray()) {
                return paths;
            }

            JsonArray files = root.getAsJsonArray();
            for (JsonElement element : files) {
                if (!element.isJsonObject()) {
                    continue;
                }
                boolean success = !element.getAsJsonObject().has("success")
                        || element.getAsJsonObject().get("success").getAsBoolean();
                String path = element.getAsJsonObject().has("path")
                        ? element.getAsJsonObject().get("path").getAsString()
                        : "";
                if (success && path != null && !path.isEmpty()) {
                    paths.add(path);
                }
            }
        } catch (Exception ignored) {
            // 历史记录损坏时不影响正常上传和其他记录。
        }
        return paths;
    }
}
