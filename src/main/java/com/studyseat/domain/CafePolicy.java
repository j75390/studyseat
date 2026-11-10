package com.studyseat.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class CafePolicy {

    @Id
    private Long id;
    private int openHour;
    private int closeHour;
    private int maxHoursPerRsv;
    private int cancelBeforeMin;
    private int noshowGraceMin;
    private int earnPointPerHr;
    private int pricePointPerHr;

    protected CafePolicy() {
    }

    public int getOpenHour() { return openHour; }
    public int getCloseHour() { return closeHour; }
    public int getMaxHoursPerRsv() { return maxHoursPerRsv; }
    public int getCancelBeforeMin() { return cancelBeforeMin; }
    public int getNoshowGraceMin() { return noshowGraceMin; }
    public int getEarnPointPerHr() { return earnPointPerHr; }
    public int getPricePointPerHr() { return pricePointPerHr; }
}
