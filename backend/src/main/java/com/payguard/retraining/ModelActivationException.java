package com.payguard.retraining;

public class ModelActivationException extends RuntimeException {

    public ModelActivationException(String message) {
        super(message);
    }

    public ModelActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
