package com.studyseat;

import org.springframework.jdbc.core.JdbcTemplate;

/** 시험이 끝날 때 데이터를 지운다. 구역·좌석·운영 정책(시드)은 남긴다. */
final class TestDb {

    /** 성별 전용·고정석이 아닌 일반 좌석 중 idx번째(id 순). 시험이 좌석 속성과 상관없이 예약할 수 있게 한다. */
    static Long ordinarySeat(JdbcTemplate jdbc, long zoneId, int idx) {
        return jdbc.queryForList("SELECT id FROM seat WHERE zone_id = ? AND active = TRUE AND gender = 'A' AND fixed_seat = FALSE ORDER BY id",
                Long.class, zoneId).get(idx);
    }


    private TestDb() {
    }

    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM occupancy_daily");
        jdbc.update("DELETE FROM occupancy_hourly");
        jdbc.update("DELETE FROM favorite_branch");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM inquiry");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM reservation_slot");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM member");
    }
}
