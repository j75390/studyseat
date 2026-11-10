package com.studyseat.service;

/** 포인트 잔액이 부족할 때. */
public class InsufficientPointException extends RuntimeException {
    public InsufficientPointException(String message) {
        super(message);
    }
}
