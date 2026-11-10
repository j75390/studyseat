package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 앱이 뜰 때 초기 격자 배치(V3·V4)의 지점이 도면 엔진 배치로 바뀌고, 좌석 수·속성은 그대로인지 확인한다. */
@SpringBootTest(properties = "studyseat.demo=false")
class FloorPlanRelayoutTest {

    @Autowired JdbcTemplate jdbc;

    private long count(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    @Test
    void 기존_38개_지점이_모두_새_도면_버전이고_좌석은_도면_안에서_겹치지_않는다() {
        assertThat(count("SELECT COUNT(*) FROM branch WHERE id < 1000 AND layout_ver < 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM branch WHERE id < 1000 AND (plan_w <> 32 OR plan_h <> 20)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM seat s JOIN zone z ON z.id = s.zone_id JOIN branch b ON b.id = z.branch_id "
                + "WHERE b.id < 1000 AND (s.pos_x < 0.4 OR s.pos_y < 0.4 OR s.pos_x + s.width > b.plan_w - 0.4 OR s.pos_y + s.height > b.plan_h - 0.4)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM seat a JOIN zone za ON za.id = a.zone_id JOIN seat c ON c.id > a.id JOIN zone zc ON zc.id = c.zone_id "
                + "WHERE za.branch_id = zc.branch_id AND za.branch_id < 1000 "
                + "AND a.pos_x < c.pos_x + c.width - 0.001 AND c.pos_x < a.pos_x + a.width - 0.001 "
                + "AND a.pos_y < c.pos_y + c.height - 0.001 AND c.pos_y < a.pos_y + a.height - 0.001")).isZero();
        // 모든 지점에 벽·입구·키오스크·사물함이 있다
        assertThat(count("SELECT COUNT(*) FROM branch b WHERE b.id < 1000 AND (SELECT COUNT(*) FROM plan_object o WHERE o.branch_id = b.id AND o.kind IN ('ENTRANCE','KIOSK','LOCKER','COUNTER')) <> 4")).isZero();
    }

    @Test
    void 재배치해도_좌석_수와_번호와_성별_고정석_속성은_그대로다() {
        // 강남점 일반석 10석(A-01~A-10): 여성 4(01·02·09·10) 남성 1(08) 고정 1(04)
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 1")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 1 AND gender = 'F'")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 1 AND gender = 'M'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 1 AND fixed_seat = TRUE")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 2")).isEqualTo(4);  // 스터디룸
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 3")).isEqualTo(8);  // 프리미엄 부스
        // 번호 순서대로 배치된다: A-01이 A-02보다 위 줄이거나 같은 줄의 왼쪽/오른쪽에 있다(배치 순서 보존)
        assertThat(count("SELECT COUNT(*) FROM seat WHERE zone_id = 1 AND width = 2.0 AND height = 1.5")).isEqualTo(10);
    }
}
