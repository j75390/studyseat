package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String loginId;
    private String password;
    private String name;
    private String phone;
    private String role;
    private LocalDateTime createdAt;
    /** F / M / null(입력 안 함) */
    private String gender;

    protected Member() {
    }

    public Member(String loginId, String password, String name, String phone, String role, LocalDateTime createdAt) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.createdAt = createdAt;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getGender() { return gender; }
    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
