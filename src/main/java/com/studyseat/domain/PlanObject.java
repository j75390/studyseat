package com.studyseat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** 도면에 그려지는 입구·카운터·창가·화장실 같은 장식 요소. */
@Entity
public class PlanObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long branchId;
    private String kind;
    private String name;
    @Column(name = "pos_x")
    private double posX;
    @Column(name = "pos_y")
    private double posY;
    private double width;
    private double height;

    protected PlanObject() {
    }

    public Long getId() { return id; }
    public Long getBranchId() { return branchId; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
}
