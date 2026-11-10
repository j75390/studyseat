package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyseat.domain.Member;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.service.PointService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.SeatTakenException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "studyseat.demo=false")
class ReservationConcurrencyTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 8, 0);
    static final LocalDateTime TEN = LocalDateTime.of(2026, 11, 10, 10, 0);

    @Autowired ReservationService service;
    @Autowired MemberRepository members;
    @Autowired SeatRepository seats;
    @Autowired JdbcTemplate jdbc;

    @Autowired PointService points;

    @AfterEach
    void cleanUp() {
        TestDb.clean(jdbc);
    }

    private List<Long> createMembers(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long id = members.save(new Member("t" + i, "pw", "테스터" + i, null, "MEMBER", NOW)).getId();
            points.charge(id, 100_000, NOW);
            ids.add(id);
        }
        return ids;
    }

    /** 모든 스레드를 한 번에 출발시켜 동시에 예약을 시도하고 (성공, 충돌) 개수를 센다. */
    private int[] race(List<Long> memberIds, Long seatId, List<LocalDateTime> starts, int hours) throws Exception {
        int n = memberIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.reserve(memberIds.get(idx), seatId, starts.get(idx), hours, NOW);
                    ok.incrementAndGet();
                } catch (SeatTakenException e) {
                    taken.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return new int[] {ok.get(), taken.get()};
    }

    @Test
    void 같은_좌석_같은_시간에_20명이_동시에_예약해도_한_명만_성공한다() throws Exception {
        Long seatId = TestDb.ordinarySeat(jdbc, 1, 0);
        List<Long> ids = createMembers(20);
        List<LocalDateTime> starts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            starts.add(TEN);
        }

        int[] result = race(ids, seatId, starts, 2);

        assertThat(result[0]).isEqualTo(1);
        assertThat(result[1]).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot WHERE seat_id = ?", Integer.class, seatId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 시간이_겹치는_예약이_섞여도_같은_좌석_같은_시간이_두_번_잡히지_않는다() throws Exception {
        Long seatId = TestDb.ordinarySeat(jdbc, 1, 0);
        List<Long> ids = createMembers(12);
        List<LocalDateTime> starts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            starts.add(TEN.plusHours(i % 3)); // 10시, 11시, 12시 시작(각 2시간)이 서로 겹침
        }

        int[] result = race(ids, seatId, starts, 2);

        assertThat(result[0] + result[1]).isEqualTo(12);
        assertThat(result[0]).isBetween(1, 2); // 10~12시와 12~14시는 동시에 가능, 가운데(11~13시)가 이기면 1건
        Integer duplicated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT seat_id, slot_start FROM reservation_slot "
                        + "GROUP BY seat_id, slot_start HAVING COUNT(*) > 1) d", Integer.class);
        assertThat(duplicated).isZero();
        // 성공한 예약 수 x 2시간 = 슬롯 수 (실패한 예약이 슬롯을 남기지 않음)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class)).isEqualTo(result[0] * 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(result[0]);
    }

    @Test
    void 운영시간_밖_지난시간_정시가_아닌_예약은_거부된다() {
        Long seatId = TestDb.ordinarySeat(jdbc, 1, 0);
        Long memberId = createMembers(1).get(0);
        assertThatThrownBy(() -> service.reserve(memberId, seatId, TEN.withHour(8), 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(memberId, seatId, TEN.withHour(22), 2, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(memberId, seatId, TEN.withMinute(30), 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(memberId, seatId, NOW.minusDays(1), 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(memberId, seatId, TEN, 9, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        service.reserve(memberId, seatId, TEN.withHour(21), 2, NOW); // 21~23시는 가능
    }
}
