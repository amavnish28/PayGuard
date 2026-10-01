package com.payguard.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class VerdictAlreadyExistsException extends RuntimeException {

    public VerdictAlreadyExistsException(String message) {
        super(message);
    }
}
