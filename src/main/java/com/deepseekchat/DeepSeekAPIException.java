package com.deepseekchat;

/**
 * DeepSeek API 请求异常
 */
public class DeepSeekAPIException extends Exception {

    private final int httpCode;

    public DeepSeekAPIException(int httpCode, String message) {
        super(message);
        this.httpCode = httpCode;
    }

    public DeepSeekAPIException(String message) {
        this(-1, message);
    }

    public int getHttpCode() {
        return httpCode;
    }
}
