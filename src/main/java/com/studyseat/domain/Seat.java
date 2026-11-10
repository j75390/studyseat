package com.studyseat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long zoneId;
    private String label;
    private boolean active = true;
    @Column(name = "pos_x")
    private double posX;
    @Column(name = "pos_y")
    private double posY;
    private double width = 2;
    private double height = 2;
    private String seatType = "DESK";
    private int capacity = 1;
    /** A=누구나, F=여성 전용, M=남성 전용 */
    private String gender = "A";
    /** 고정석: 월 이용권 회원 전용이라 시간권으로 예약할 수 없다 */
    private boolean fixedSeat;

    protected Seat() {
    }

    public void toggleActive() {
        this.active = !this.active;
    }

    public Long getId() { return id; }
    public Long getZoneId() { return zoneId; }
    public String getLabel() { return label; }
    public boolean isActive() { return active; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public String getSeatType() { return seatType; }
    public int getCapacity() { return capacity; }
    public String getGender() { return gender; }
    public boolean isFixedSeat() { return fixedSeat; }
}
