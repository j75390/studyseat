package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long reservationId;
    private Long memberId;
    private int rating;
    private String content;
    private LocalDateTime createdAt;

    protected Review() {
    }

    public Review(Long reservationId, Long memberId, int rating, String content, LocalDateTime createdAt) {
        this.reservationId = reservationId;
        this.memberId = memberId;
        this.rating = rating;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getMemberId() { return memberId; }
    public int getRating() { return rating; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
