package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/** 예약 1건을 1시간 단위로 쪼갠 행. (seat_id, slot_start) 유니크가 이중 예약을 막는다. */
@Entity
public class ReservationSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long reservationId;
    private Long seatId;
    private LocalDateTime slotStart;

    protected ReservationSlot() {
    }

    public ReservationSlot(Long reservationId, Long seatId, LocalDateTime slotStart) {
        this.reservationId = reservationId;
        this.seatId = seatId;
        this.slotStart = slotStart;
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getSeatId() { return seatId; }
    public LocalDateTime getSlotStart() { return slotStart; }
}
