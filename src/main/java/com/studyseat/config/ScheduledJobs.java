package com.studyseat.config;

import com.studyseat.service.OccupancyService;
import com.studyseat.service.ReservationService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final ReservationService reservations;
    private final OccupancyService occupancy;
    private final Clock clock;

    public ScheduledJobs(ReservationService reservations, OccupancyService occupancy, Clock clock) {
        this.reservations = reservations;
        this.occupancy = occupancy;
        this.clock = clock;
    }

    /** 1분마다: 체크인 안 한 예약을 노쇼로, 퇴실 안 한 이용을 자동 종료로 정리 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweep() {
        int[] r = reservations.sweep(LocalDateTime.now(clock));
        if (r[0] + r[1] > 0) {
            log.info("예약 점검: 노쇼 {}건, 자동 종료 {}건", r[0], r[1]);
        }
    }

    /** 매시 5분: 방금 끝난 시간의 혼잡도 집계 */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    public void aggregate() {
        occupancy.aggregatePreviousHour(LocalDateTime.now(clock));
    }
}
