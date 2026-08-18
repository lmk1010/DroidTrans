package com.mk.androidtransfer.network;

import com.mk.androidtransfer.model.ApiResponse;
import com.mk.androidtransfer.model.PhotoListRequest;

import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * 网络API服务接口
 */
public interface ApiService {

    /**
     * 设备连接注册接口
     */
    @POST("/api/wifi/connect")
    Call<Map<String, Object>> connectDevice(@Body Map<String, String> request);

    /**
     * 获取WiFi服务器信息
     */
    @GET("/api/wifi/info")
    Call<ApiResponse<Object>> getWifiInfo(
            @Query("device_id") String deviceId,
            @Query("device_name") String deviceName
    );

    /**
     * 上传照片列表（不上传文件内容）
     */
    @POST("/api/wifi/upload_photo_list")
    Call<ApiResponse<Object>> uploadPhotoList(@Body PhotoListRequest request);

    /**
     * 上传单个照片文件
     */
    @Multipart
    @POST("/api/wifi/upload_photo")
    Call<ApiResponse<Object>> uploadPhoto(
            @Part MultipartBody.Part file,
            @Part("relative_path") RequestBody relativePath,
            @Part("output_dir") RequestBody outputDir
    );

    /**
     * 上传单个照片文件（新版本，支持device_id）
     */
    @Multipart
    @POST("/api/wifi/upload_photo")
    Call<Map<String, Object>> uploadPhotoMultipart(
            @Part MultipartBody.Part file,
            @Part("device_id") RequestBody deviceId,
            @Part("relative_path") RequestBody relativePath
    );
    
    /**
     * 上传单个照片文件（支持断点续传，包含file_size）
     */
    @Multipart
    @POST("/api/wifi/upload_photo")
    Call<Map<String, Object>> uploadPhotoMultipart(
            @Part MultipartBody.Part file,
            @Part("device_id") RequestBody deviceId,
            @Part("relative_path") RequestBody relativePath,
            @Part("file_size") RequestBody fileSize
    );

    /**
     * 获取WiFi模式状态
     */
    @GET("/api/wifi/status")
    Call<ApiResponse<Object>> getWifiStatus();

    /**
     * 初始化上传会话
     */
    @POST("/api/upload/init")
    Call<Map<String, Object>> initUpload(@Body Map<String, Object> request);

    /**
     * 获取上传进度
     */
    @GET("/api/upload/progress/{device_id}")
    Call<Map<String, Object>> getUploadProgress(@Path("device_id") String deviceId);

    /**
     * 更新上传进度
     */
    @POST("/api/upload/update")
    Call<Map<String, Object>> updateUploadProgress(@Body Map<String, Object> request);

    /**
     * 取消上传
     */
    @POST("/api/upload/cancel/{device_id}")
    Call<Map<String, Object>> cancelUpload(@Path("device_id") String deviceId);
}
