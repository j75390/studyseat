package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "studyseat.demo=false")
class SchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 마이그레이션이_적용되어_14개_테이블과_38개_지점이_생긴다() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' "
                        + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class);
        assertThat(count).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch", Integer.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat", Integer.class)).isGreaterThan(900);
    }
}
