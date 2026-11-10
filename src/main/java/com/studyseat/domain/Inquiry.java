package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private String title;
    private String content;
    private String answer;
    private LocalDateTime answeredAt;
    private LocalDateTime createdAt;

    protected Inquiry() {
    }

    public Inquiry(Long memberId, String title, String content, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
    }

    public void answer(String answer, LocalDateTime at) {
        this.answer = answer;
        this.answeredAt = at;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getAnswer() { return answer; }
    public LocalDateTime getAnsweredAt() { return answeredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
