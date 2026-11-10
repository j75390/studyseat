package com.studyseat.web;

import com.studyseat.domain.PlanObject;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.service.BranchService;
import java.util.List;
import java.util.Locale;

/** 도면 좌표(격자 칸)를 화면에서 쓰는 퍼센트 위치로 바꾼다. */
final class PlanMapper {

    private PlanMapper() {
    }

    record SeatDto(Long id, String label, String shortLabel, String type, String zone, int price, String state,
                   String style, int capacity, String gender, boolean endingSoon, String tip) {
    }

    record ZoneDto(String name, int price, String type, String style) {
    }

    record ObjDto(String kind, String name, String style) {
    }

    record PlanDto(int w, int h, List<SeatDto> seats, List<ZoneDto> zones, List<ObjDto> objects, int free, int total) {
    }

    private static String style(double x, double y, double w, double h, int pw, int ph) {
        return String.format(Locale.ROOT, "left:%.2f%%;top:%.2f%%;width:%.2f%%;height:%.2f%%",
                x * 100 / pw, y * 100 / ph, w * 100 / pw, h * 100 / ph);
    }

    /** 좌석에 마우스를 올렸을 때 보이는 설명 */
    private static String tip(Seat s, Zone z, BranchService.SeatView v) {
        StringBuilder b = new StringBuilder(z.getName()).append(" ").append(s.getLabel())
                .append(" · ").append(String.format("%,d", z.getPricePointPerHr())).append("P/시간");
        if ("F".equals(s.getGender())) {
            b.append(" · 여성 전용");
        } else if ("M".equals(s.getGender())) {
            b.append(" · 남성 전용");
        }
        switch (v.state()) {
            case TAKEN -> b.append(" (예약됨)");
            case MINE -> b.append(" (내 예약)");
            case INACTIVE -> b.append(" (이용 불가)");
            case FIXED -> b.append(" (고정석 · 월 이용권 전용)");
            case RESTRICTED -> b.append(" (성별 전용 좌석)");
            default -> {
                if (v.endingSoon()) {
                    b.append(" · 퇴실예정석(직전 이용자가 곧 퇴실)");
                }
            }
        }
        return b.toString();
    }

    static PlanDto of(BranchService.PlanView p) {
        int pw = p.branch().getPlanW();
        int ph = p.branch().getPlanH();
        List<SeatDto> seats = p.seats().stream().map(v -> {
            Seat s = v.seat();
            Zone z = v.zone();
            String shortLabel = "ROOM".equals(s.getSeatType())
                    ? "룸" + Integer.parseInt(s.getLabel().substring(s.getLabel().indexOf('-') + 1))
                    : s.getLabel().substring(s.getLabel().indexOf('-') + 1);
            return new SeatDto(s.getId(), s.getLabel(), shortLabel, s.getSeatType(), z.getName(),
                    z.getPricePointPerHr(), v.state().name(),
                    style(s.getPosX(), s.getPosY(), s.getWidth(), s.getHeight(), pw, ph), s.getCapacity(), s.getGender(),
                    v.endingSoon(), tip(s, z, v));
        }).toList();
        List<ZoneDto> zones = p.zones().stream().map(z -> new ZoneDto(z.getName(), z.getPricePointPerHr(),
                z.getZoneType(), style(z.getAreaX(), z.getAreaY(), z.getAreaW(), z.getAreaH(), pw, ph))).toList();
        List<ObjDto> objs = p.objects().stream().map((PlanObject o) -> new ObjDto(o.getKind(), o.getName(),
                style(o.getPosX(), o.getPosY(), o.getWidth(), o.getHeight(), pw, ph))).toList();
        return new PlanDto(pw, ph, seats, zones, objs, p.freeCount(), p.totalCount());
    }
}
