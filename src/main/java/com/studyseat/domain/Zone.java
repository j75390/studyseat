package com.studyseat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String zoneType;
    private Long branchId;
    private int pricePointPerHr;
    @Column(name = "area_x")
    private double areaX;
    @Column(name = "area_y")
    private double areaY;
    @Column(name = "area_w")
    private double areaW;
    @Column(name = "area_h")
    private double areaH;

    protected Zone() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getZoneType() { return zoneType; }
    public Long getBranchId() { return branchId; }
    public int getPricePointPerHr() { return pricePointPerHr; }
    public double getAreaX() { return areaX; }
    public double getAreaY() { return areaY; }
    public double getAreaW() { return areaW; }
    public double getAreaH() { return areaH; }
}
