package com.studyseat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도면 배치 버전이 2 미만인 지점(초기 격자 배치)을 도면 엔진({@link FloorPlanLayout})으로 다시 배치한다.
 * 좌석 수·번호·속성(성별 전용·고정석 등)은 그대로 두고 위치·크기, 구역 영역, 벽·시설만 바꾼다.
 */
@Service
public class FloorPlanService {

    private static final Logger log = LoggerFactory.getLogger(FloorPlanService.class);
    private static final int CURRENT_VERSION = 2;

    private final JdbcTemplate jdbc;

    public FloorPlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 다시 배치한 지점 수를 돌려준다. */
    @Transactional
    public int relayoutOutdated() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM branch WHERE layout_ver < ? ORDER BY id", Long.class, CURRENT_VERSION);
        for (Long id : ids) {
            relayout(id);
        }
        if (!ids.isEmpty()) {
            log.info("도면 재배치: {}개 지점", ids.size());
        }
        return ids.size();
    }

    @Transactional
    public void relayout(Long branchId) {
        List<Map<String, Object>> zones = jdbc.queryForList("SELECT id, zone_type FROM zone WHERE branch_id = ? ORDER BY id", branchId);
        int desk = 0;
        int booth = 0;
        int lounge = 0;
        int room = 0;
        List<Object[]> seatRows = new ArrayList<>(); // [seatId, zoneType]
        for (Map<String, Object> z : zones) {
            String type = (String) z.get("zone_type");
            List<Long> seatIds = jdbc.queryForList("SELECT id FROM seat WHERE zone_id = ? ORDER BY label", Long.class, z.get("id"));
            for (Long sid : seatIds) {
                seatRows.add(new Object[] {sid, type, z.get("id")});
            }
            switch (type) {
                case "READING" -> desk += seatIds.size();
                case "BOOTH" -> booth += seatIds.size();
                case "LOUNGE" -> lounge += seatIds.size();
                default -> room += seatIds.size();
            }
        }
        int style = (int) (branchId % 4);
        if (desk > FloorPlanLayout.maxDesks(style)) {
            style = 2 + (style % 2); // 일반석이 많으면 도서관형
        }
        FloorPlanLayout.Layout layout = FloorPlanLayout.arrange(style, desk, booth, lounge, room);

        int di = 0;
        int bi = 0;
        int li = 0;
        int ri = 0;
        List<Object[]> seatUpdates = new ArrayList<>();
        for (Object[] s : seatRows) {
            FloorPlanLayout.Rect p = switch ((String) s[1]) {
                case "READING" -> layout.desks().get(di++);
                case "BOOTH" -> layout.booths().get(bi++);
                case "LOUNGE" -> layout.lounges().get(li++);
                default -> layout.rooms().get(ri++);
            };
            seatUpdates.add(new Object[] {p.x(), p.y(), p.w(), p.h(), s[0]});
        }
        jdbc.batchUpdate("UPDATE seat SET pos_x = ?, pos_y = ?, width = ?, height = ? WHERE id = ?", seatUpdates);

        for (Map<String, Object> z : zones) {
            FloorPlanLayout.Rect a = switch ((String) z.get("zone_type")) {
                case "READING" -> layout.deskZone();
                case "BOOTH" -> layout.boothZone();
                case "LOUNGE" -> layout.loungeZone();
                default -> layout.roomZone();
            };
            jdbc.update("UPDATE zone SET area_x = ?, area_y = ?, area_w = ?, area_h = ? WHERE id = ?",
                    a.x(), a.y(), a.w(), a.h(), z.get("id"));
        }
        jdbc.update("UPDATE branch SET plan_w = ?, plan_h = ?, layout_ver = ? WHERE id = ?",
                FloorPlanLayout.W, FloorPlanLayout.H, CURRENT_VERSION, branchId);
        jdbc.update("DELETE FROM plan_object WHERE branch_id = ?", branchId);
        List<Object[]> objs = new ArrayList<>();
        for (FloorPlanLayout.Obj o : layout.objects()) {
            objs.add(new Object[] {branchId, o.kind(), o.name(), o.rect().x(), o.rect().y(), o.rect().w(), o.rect().h()});
        }
        jdbc.batchUpdate("INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (?, ?, ?, ?, ?, ?, ?)", objs);
    }
}
