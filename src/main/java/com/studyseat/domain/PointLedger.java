package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/** 포인트 원장. 쌓기만 하고 고치지 않는다. 잔액 = amount 합계. */
@Entity
public class PointLedger {

    public static final String CHARGE = "CHARGE";
    public static final String USE = "USE";
    public static final String REFUND = "REFUND";
    public static final String EARN = "EARN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private int amount;
    private String reason;
    private Long reservationId;
    private LocalDateTime createdAt;

    protected PointLedger() {
    }

    public PointLedger(Long memberId, int amount, String reason, Long reservationId, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.amount = amount;
        this.reason = reason;
        this.reservationId = reservationId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public int getAmount() { return amount; }
    public String getReason() { return reason; }
    public Long getReservationId() { return reservationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
