package com.studyseat.config;

import com.studyseat.domain.Member;
import com.studyseat.repository.MemberRepository;
import com.studyseat.service.DemoDataService;
import com.studyseat.service.FloorPlanService;
import com.studyseat.service.MemberService;
import com.studyseat.service.NationwideSeeder;
import com.studyseat.service.OccupancyService;
import com.studyseat.service.PointService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜰 때 한 번 실행:
 * 1) 체험용 계정(관리자·일반 회원), 전국 지점(실제 읍·면·동 위에 가상 지점), 오늘의 예약·지난 3일 통계를 만든다
 *    (studyseat.demo=false면 건너뜀)
 * 2) 앱이 꺼져 있던 구간의 혼잡도 통계를 보충한다 (시간별 먼저, 일별은 그다음)
 */
@Component
public class StartupRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final MemberService memberService;
    private final MemberRepository members;
    private final PointService points;
    private final NationwideSeeder nationwide;
    private final FloorPlanService floorPlans;
    private final DemoDataService demoData;
    private final OccupancyService occupancy;
    private final Clock clock;
    private final boolean demo;

    public StartupRunner(MemberService memberService, MemberRepository members, PointService points,
                         NationwideSeeder nationwide, FloorPlanService floorPlans, DemoDataService demoData, OccupancyService occupancy,
                         Clock clock, @Value("${studyseat.demo:true}") boolean demo) {
        this.memberService = memberService;
        this.members = members;
        this.points = points;
        this.nationwide = nationwide;
        this.floorPlans = floorPlans;
        this.demoData = demoData;
        this.occupancy = occupancy;
        this.clock = clock;
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        floorPlans.relayoutOutdated(); // 초기 격자 배치(V3·V4)의 지점을 도면 엔진 배치로 바꾼다
        if (demo) {
            memberService.createAdmin("admin", "admin1234", "관리자", now);
            if (members.findByLoginId("demo").isEmpty()) {
                long t0 = System.currentTimeMillis();
                Member demoMember = memberService.signup("demo", "demo1234", "데모회원", null, "F", now);
                points.charge(demoMember.getId(), 30_000, now);
                int created = nationwide.seed();
                log.info("전국 지점 {}곳 생성 ({}ms)", created, System.currentTimeMillis() - t0);
                // 지난 이용 기록(통계용)은 체험 계정이 아닌 별도 회원에 붙여 '내 예약' 화면이 깔끔하게 한다
                Member visitors = memberService.signup("visitors", "visitors-not-for-login-1", "방문자", null, now);
                demoData.seed(visitors.getId(), now);
                log.info("데모 데이터 생성 완료 ({}ms)", System.currentTimeMillis() - t0);
            }
        }
        OccupancyService.BackfillResult r = occupancy.backfill(now);
        log.info("혼잡도 누락 보충: 시간별 {}건, 일별 {}건", r.hourly(), r.daily());
    }
}
