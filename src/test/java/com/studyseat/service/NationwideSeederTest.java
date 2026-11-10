package com.studyseat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 전국 지점 분포 규칙(실제 행정구역 위에 어떻게 놓는가)을 확인한다. DB 없이 계획 단계만 시험한다. */
class NationwideSeederTest {

    private static final List<NationwideSeeder.Area> AREAS = NationwideSeeder.loadAreas();
    private static final List<Object[]> PLAN = NationwideSeeder.plan(AREAS, 20261109L);

    private static NationwideSeeder.Area area(Object[] p) {
        return (NationwideSeeder.Area) p[0];
    }

    @Test
    void 행정구역_데이터는_16개_시도_256개_시군구_3500개_넘는_읍면동이고_좌표가_국내다() {
        assertThat(AREAS.stream().map(NationwideSeeder.Area::sido).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("서울", "부산", "대구", "인천", "대전", "울산", "세종", "경기", "강원", "충북", "충남",
                        "전북", "경북", "경남", "제주", "전남광주");
        assertThat(AREAS.stream().map(a -> a.sido() + a.sgg()).collect(Collectors.toSet())).hasSize(256);
        assertThat(AREAS.size()).isGreaterThan(3500);
        assertThat(AREAS).allSatisfy(a -> {
            assertThat(a.lat()).isBetween(33.0, 38.7);
            assertThat(a.lng()).isBetween(124.0, 131.0);
            assertThat(a.dong()).doesNotContain(","); // CSV가 깨지지 않도록
        });
    }

    @Test
    void 인천_옹진군_대청면은_실제_행정구역으로_들어_있다() {
        assertThat(AREAS).anyMatch(a -> a.sido().equals("인천") && a.sgg().equals("옹진군") && a.dong().equals("대청면"));
    }

    @Test
    void 모든_시군구에_지점이_최소_한_곳_있다() {
        Set<String> covered = PLAN.stream().map(p -> area(p).sido() + " " + area(p).sgg()).collect(Collectors.toSet());
        Set<String> all = AREAS.stream().map(a -> a.sido() + " " + a.sgg()).collect(Collectors.toSet());
        assertThat(covered).containsAll(all);
    }

    @Test
    void 지점_수는_도심에_많고_읍면에도_드물게_있다() {
        long metro = PLAN.stream().filter(p -> "동".equals(area(p).kind())).count();
        long eup = PLAN.stream().filter(p -> "읍".equals(area(p).kind())).count();
        long myeon = PLAN.stream().filter(p -> "면".equals(area(p).kind())).count();
        assertThat(PLAN.size()).isBetween(550, 900); // 실제 약 6,944곳(2024.10, KB국민카드)의 10% 안팎
        assertThat(eup).isGreaterThan(15);
        assertThat(myeon).isGreaterThan(15);
        assertThat(metro).isGreaterThan(eup + myeon); // 대부분은 동 지역
        // 읍·면 한 곳당 지점 수는 동 한 곳당보다 훨씬 적다
        long dongAreas = AREAS.stream().filter(a -> "동".equals(a.kind())).count();
        long myeonAreas = AREAS.stream().filter(a -> "면".equals(a.kind())).count();
        assertThat((double) myeon / myeonAreas).isLessThan(((double) metro / dongAreas) / 3);
    }

    @Test
    void 대전은_표본_지역이라_다른_같은_규모_지역보다_촘촘하다() {
        long daejeon = PLAN.stream().filter(p -> area(p).sido().equals("대전")).count();
        long daejeonAreas = AREAS.stream().filter(a -> a.sido().equals("대전")).count();
        long daegu = PLAN.stream().filter(p -> area(p).sido().equals("대구")).count();
        long daeguAreas = AREAS.stream().filter(a -> a.sido().equals("대구")).count();
        assertThat(daejeon).isGreaterThanOrEqualTo(40);
        assertThat((double) daejeon / daejeonAreas).isGreaterThan((double) daegu / daeguAreas);
    }

    @Test
    void 기대_지점_수는_행정동_종류와_도시_규모_순서를_따른다() {
        NationwideSeeder.Area metroDong = new NationwideSeeder.Area("부산", "해운대구", "우동", "동", 35.1, 129.1);
        NationwideSeeder.Area cityDong = new NationwideSeeder.Area("경기", "수원시팔달구", "인계동", "동", 37.2, 127.0);
        NationwideSeeder.Area eup = new NationwideSeeder.Area("충남", "논산시", "강경읍", "읍", 36.1, 127.0);
        NationwideSeeder.Area myeon = new NationwideSeeder.Area("전북", "임실군", "삼계면", "면", 35.5, 127.2);
        assertThat(NationwideSeeder.expected(metroDong)).isGreaterThan(NationwideSeeder.expected(cityDong));
        assertThat(NationwideSeeder.expected(cityDong)).isGreaterThan(NationwideSeeder.expected(eup));
        assertThat(NationwideSeeder.expected(eup)).isGreaterThan(NationwideSeeder.expected(myeon));
    }
}
