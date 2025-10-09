package com.mk.androidtransfer.model;

/**
 * API响应数据模型
 */
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private String error;
    private T data;
    private int count;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
