package com.nukacast.app.drama;

public final class DramaException extends Exception {
    public final String code;

    public DramaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DramaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
