package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyseat.service.NationwideSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 전국 지점을 실제로 DB에 넣었을 때의 결과를 확인한다. 다른 시험과 데이터가 섞이지 않도록 별도 DB를 쓴다. */
@SpringBootTest(properties = {"studyseat.demo=false",
        "spring.datasource.url=jdbc:h2:mem:seedtest;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class NationwideSeedingTest {

    @Autowired NationwideSeeder seeder;
    @Autowired JdbcTemplate jdbc;

    private long count(String sql) {
        Long c = jdbc.queryForObject(sql, Long.class);
        return c == null ? 0 : c;
    }

    @Test
    void 전국_지점이_저장되고_다시_실행해도_중복되지_않는다() {
        long before = count("SELECT COUNT(*) FROM branch");
        int created = seeder.seed();

        assertThat(created).isBetween(550, 900);
        assertThat(count("SELECT COUNT(*) FROM branch")).isEqualTo(before + created);
        assertThat(seeder.seed()).isZero(); // 멱등

        // 지점 이름이 겹치지 않고, 모든 지점에 구역·좌석·도면 요소가 있다
        assertThat(count("SELECT COUNT(*) FROM (SELECT name FROM branch GROUP BY name HAVING COUNT(*) > 1) d")).isZero();
        assertThat(count("SELECT COUNT(*) FROM branch b WHERE b.id >= 1000 AND NOT EXISTS (SELECT 1 FROM zone z WHERE z.branch_id = b.id)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM branch b WHERE b.id >= 1000 AND (SELECT COUNT(*) FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.branch_id = b.id) < 5")).isZero();
        assertThat(count("SELECT COUNT(*) FROM branch b WHERE b.id >= 1000 AND NOT EXISTS (SELECT 1 FROM plan_object o WHERE o.branch_id = b.id AND o.kind = 'ENTRANCE')")).isZero();
        assertThat(count("SELECT COUNT(*) FROM branch b WHERE b.id >= 1000 AND (SELECT COUNT(*) FROM plan_object o WHERE o.branch_id = b.id AND o.kind = 'WALL') < 4")).isZero();
        // 좌석은 지점 도면 안에 있고, 같은 지점의 좌석끼리 겹치지 않는다
        assertThat(count("SELECT COUNT(*) FROM seat s JOIN zone z ON z.id = s.zone_id JOIN branch b ON b.id = z.branch_id "
                + "WHERE b.id >= 1000 AND (s.pos_x < 0 OR s.pos_y < 0 OR s.pos_x + s.width > b.plan_w OR s.pos_y + s.height > b.plan_h)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM seat a JOIN zone za ON za.id = a.zone_id JOIN seat c ON c.id > a.id JOIN zone zc ON zc.id = c.zone_id "
                + "WHERE za.branch_id = zc.branch_id AND za.branch_id >= 1000 "
                + "AND a.pos_x < c.pos_x + c.width - 0.001 AND c.pos_x < a.pos_x + a.width - 0.001 "
                + "AND a.pos_y < c.pos_y + c.height - 0.001 AND c.pos_y < a.pos_y + a.height - 0.001")).isZero();
        // 인천 옹진군에도 지점이 있다(섬 지역 포함 모든 시·군·구)
        assertThat(count("SELECT COUNT(*) FROM branch WHERE address LIKE '인천 옹진군%'")).isGreaterThanOrEqualTo(1);
        // 시·도 이름은 약칭이고 옛 이름·긴 이름이 섞이지 않는다
        assertThat(count("SELECT COUNT(*) FROM branch WHERE address LIKE '%광역시%' OR address LIKE '%특별시%' OR address LIKE '%통합%'")).isZero();
    }
}
