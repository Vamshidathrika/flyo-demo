package com.flyo.rescue.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class ApprovalRequiredException extends RuntimeException {
    public ApprovalRequiredException(String message) {
        super(message);
    }
}
