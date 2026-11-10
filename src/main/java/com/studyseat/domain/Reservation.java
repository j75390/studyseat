package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private Long seatId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;
    private int usedPoint;
    private LocalDateTime checkedInAt;
    private LocalDateTime checkedOutAt;
    private LocalDateTime createdAt;

    protected Reservation() {
    }

    /** 입실용 6자리 코드(QR·키오스크) */
    private String code;

    public Reservation(Long memberId, Long seatId, LocalDateTime startAt, LocalDateTime endAt, int usedPoint,
                       String code, LocalDateTime createdAt) {
        this.code = code;
        this.memberId = memberId;
        this.seatId = seatId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = ReservationStatus.BOOKED;
        this.usedPoint = usedPoint;
        this.createdAt = createdAt;
    }

    public int hours() {
        return (int) java.time.Duration.between(startAt, endAt).toHours();
    }

    /** 연장: 끝 시각을 늘리고 사용 포인트를 더한다 */
    public void extend(int addHours, int cost) {
        this.endAt = this.endAt.plusHours(addHours);
        this.usedPoint += cost;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELED;
    }

    public void checkIn(LocalDateTime at) {
        this.status = ReservationStatus.CHECKED_IN;
        this.checkedInAt = at;
    }

    public void done(LocalDateTime at) {
        this.status = ReservationStatus.DONE;
        this.checkedOutAt = at;
    }

    public void noShow() {
        this.status = ReservationStatus.NO_SHOW;
    }

    /** 테스트·데모 데이터용: 상태를 직접 지정 */
    public void forceStatus(ReservationStatus status, LocalDateTime checkedInAt, LocalDateTime checkedOutAt) {
        this.status = status;
        this.checkedInAt = checkedInAt;
        this.checkedOutAt = checkedOutAt;
    }

    public String getCode() { return code; }
    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Long getSeatId() { return seatId; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public ReservationStatus getStatus() { return status; }
    public int getUsedPoint() { return usedPoint; }
    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public LocalDateTime getCheckedOutAt() { return checkedOutAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
