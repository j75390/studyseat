package com.studyseat.service;

/** 이미 다른 사람이 예약한 시간대를 잡으려 할 때. */
public class SeatTakenException extends RuntimeException {
    public SeatTakenException(String message) {
        super(message);
    }
}
