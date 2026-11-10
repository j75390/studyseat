package com.studyseat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 지점. 지도에 표시되고, 운영 시간과 도면 크기를 가진다. */
@Entity
public class Branch {

    @Id
    private Long id;
    private String name;
    private String address;
    private double lat;
    private double lng;
    private String phone;
    private int openHour;
    private int closeHour;
    private String description;
    private String amenities;
    @Column(name = "plan_w")
    private int planW;
    @Column(name = "plan_h")
    private int planH;

    protected Branch() {
    }

    public boolean is24h() {
        return openHour == 0 && closeHour == 24;
    }

    public boolean isOpenAt(int hour) {
        return hour >= openHour && hour < closeHour;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public String getPhone() { return phone; }
    public int getOpenHour() { return openHour; }
    public int getCloseHour() { return closeHour; }
    public String getDescription() { return description; }
    public String getAmenities() { return amenities; }
    public int getPlanW() { return planW; }
    public int getPlanH() { return planH; }
}
