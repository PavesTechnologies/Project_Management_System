package com.example.projectmanagement.exception;

/** Thrown when an authenticated user is not allowed to act on a specific resource. Mapped to 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
