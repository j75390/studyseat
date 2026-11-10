package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyseat.domain.Member;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationSlot;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Seat;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.ReservationSlotRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.service.InsufficientPointException;
import com.studyseat.service.OccupancyService;
import com.studyseat.service.PointService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.ReviewService;
import com.studyseat.service.SeatTakenException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 예약 이후의 흐름(포인트·취소·체크인·노쇼·통계 보충)이 규칙대로 동작하는지 확인한다. */
@SpringBootTest(properties = "studyseat.demo=false")
class LifecycleTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 8, 0);
    static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 10, 0);
    static final int PRICE = 2000; // 강남점 일반석 시간당 가격
    static final int EARN = 100; // 이용 1시간당 적립

    @Autowired ReservationService service;
    @Autowired PointService points;
    @Autowired OccupancyService occupancy;
    @Autowired ReviewService reviews;
    @Autowired MemberRepository members;
    @Autowired SeatRepository seats;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationSlotRepository slots;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        TestDb.clean(jdbc);
    }

    private Long member(String loginId, int point) {
        Long id = members.save(new Member(loginId, "pw", "테스터", null, "MEMBER", NOW)).getId();
        if (point > 0) {
            points.charge(id, point, NOW);
        }
        return id;
    }

    private List<Seat> zone1Seats() {
        return seats.findByZoneIdAndActiveTrueOrderByLabel(1L).stream()
                .filter(s -> "A".equals(s.getGender()) && !s.isFixedSeat()).toList(); // 성별 전용·고정석 제외
    }

    private List<Object> parallel(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return t.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private long count(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    @Test
    void 같은_회원이_여러_좌석을_동시에_예약해도_포인트는_한_번만_쓰인다() throws Exception {
        Long m = member("spender", PRICE); // 1시간(2,000P) 한 번만 살 수 있는 잔액
        List<Seat> zs = zone1Seats();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) { // 성별 전용·고정석을 뺀 일반 좌석 4석에 동시에 시도
            Long seatId = zs.get(i).getId();
            tasks.add(() -> service.reserve(m, seatId, START, 1, NOW));
        }

        List<Object> results = parallel(tasks);

        long success = results.stream().filter(r -> r instanceof Reservation).count();
        long insufficient = results.stream().filter(r -> r instanceof InsufficientPointException).count();
        assertThat(success).isEqualTo(1);
        assertThat(insufficient).isEqualTo(3);
        assertThat(points.balance(m)).isZero(); // 음수가 되지 않는다
        assertThat(count("SELECT COUNT(*) FROM reservation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reservation_slot")).isEqualTo(1);
    }

    @Test
    void 취소하면_슬롯이_풀리고_포인트가_환불되며_두_번_환불되지_않는다() throws Exception {
        Long m = member("canceler", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 2, NOW);
        assertThat(points.balance(m)).isEqualTo(20_000 - 2 * PRICE);

        // 같은 예약을 5명이 동시에 취소해도 환불은 한 번
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                service.cancel(m, r.getId(), NOW);
                return "ok";
            });
        }
        List<Object> results = parallel(tasks);

        assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
        assertThat(points.balance(m)).isEqualTo(20_000);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE reason = 'REFUND'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reservation_slot")).isZero();
        assertThat(reservations.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELED);
        // 풀린 자리는 다른 사람이 다시 예약할 수 있다
        Long other = member("other", 20_000);
        service.reserve(other, seatId, START, 2, NOW);
    }

    @Test
    void 시작_60분_전을_넘기면_취소할_수_없다() {
        Long m = member("late", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 1, NOW);

        assertThatThrownBy(() -> service.cancel(m, r.getId(), START.minusMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        service.cancel(m, r.getId(), START.minusMinutes(60)); // 정확히 60분 전은 가능
    }

    @Test
    void 포인트가_모자라면_예약되지_않고_아무것도_남지_않는다() {
        Long m = member("poor", 50);
        Long seatId = zone1Seats().get(0).getId();

        assertThatThrownBy(() -> service.reserve(m, seatId, START, 1, NOW))
                .isInstanceOf(InsufficientPointException.class);
        assertThat(count("SELECT COUNT(*) FROM reservation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM reservation_slot")).isZero();
    }

    @Test
    void 체크인_퇴실을_하면_이용시간만큼_포인트가_한_번만_적립된다() {
        Long m = member("user", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 3, NOW);

        assertThatThrownBy(() -> service.checkIn(m, r.getId(), START.minusMinutes(11)))
                .isInstanceOf(IllegalStateException.class); // 너무 이름
        service.checkIn(m, r.getId(), START.minusMinutes(10));
        assertThatThrownBy(() -> service.checkIn(m, r.getId(), START))
                .isInstanceOf(IllegalStateException.class); // 이미 체크인
        service.checkOut(m, r.getId(), START.plusHours(2));
        assertThatThrownBy(() -> service.checkOut(m, r.getId(), START.plusHours(2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservations.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.DONE);
        assertThat(points.balance(m)).isEqualTo(20_000 - 3 * PRICE + 3 * EARN); // 3시간 이용
    }

    @Test
    void 체크인하지_않으면_노쇼가_되고_첫_시간만_막은_채_나머지_자리는_풀리며_환불은_없다() {
        Long m = member("noshow", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 3, NOW);

        assertThat(service.sweep(START.plusMinutes(10))).containsExactly(0, 0); // 유예 10분 안
        assertThat(service.sweep(START.plusMinutes(11))).containsExactly(1, 0);
        assertThat(service.sweep(START.plusMinutes(12))).containsExactly(0, 0); // 다시 돌려도 그대로

        assertThat(reservations.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(count("SELECT COUNT(*) FROM reservation_slot WHERE reservation_id = ?", r.getId())).isEqualTo(1);
        assertThat(points.balance(m)).isEqualTo(20_000 - 3 * PRICE); // 환불 없음
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE reason = 'REFUND'")).isZero();
        // 풀린 뒤 시간은 다른 사람이 예약할 수 있다
        Long other = member("other2", 20_000);
        service.reserve(other, seatId, START.plusHours(1), 2, START.plusMinutes(12));
    }

    @Test
    void 퇴실을_누르지_않아도_종료_시각에_자동_종료되고_적립은_한_번이다() {
        Long m = member("auto", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 2, NOW);
        service.checkIn(m, r.getId(), START);

        assertThat(service.sweep(START.plusHours(1))).containsExactly(0, 0); // 아직 이용 중
        assertThat(service.sweep(START.plusHours(2))).containsExactly(0, 1);
        assertThat(service.sweep(START.plusHours(3))).containsExactly(0, 0);

        assertThat(points.balance(m)).isEqualTo(20_000 - 2 * PRICE + 2 * EARN);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE reason = 'EARN'")).isEqualTo(1);
    }

    @Test
    void 후기는_이용을_마친_예약에_한_번만_쓸_수_있다() {
        Long m = member("writer", 20_000);
        Long seatId = zone1Seats().get(0).getId();
        Reservation r = service.reserve(m, seatId, START, 1, NOW);

        assertThatThrownBy(() -> reviews.write(m, r.getId(), 5, "좋아요", NOW))
                .isInstanceOf(IllegalStateException.class); // 아직 이용 전
        service.checkIn(m, r.getId(), START);
        service.checkOut(m, r.getId(), START.plusHours(1));
        reviews.write(m, r.getId(), 5, "조용하고 좋아요", NOW);

        assertThatThrownBy(() -> reviews.write(m, r.getId(), 4, "또 씀", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reviews.write(m, r.getId(), 6, "별점 범위", NOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(reviews.list(null)).hasSize(1);
        assertThat(reviews.list(null).get(0).writer()).isEqualTo("테*터");
    }

    private void pastSlot(Long m, Long seatId, LocalDateTime at) {
        Reservation r = new Reservation(m, seatId, at, at.plusHours(1), 0, null, at.minusDays(1));
        r.forceStatus(ReservationStatus.DONE, at, at.plusHours(1));
        reservations.save(r);
        slots.save(new ReservationSlot(r.getId(), seatId, at));
    }

    @Test
    void 비어_있던_구간만_시간별_일별_통계로_채우고_다시_돌려도_중복되지_않는다() {
        Long m = member("stat", 0);
        List<Seat> zs = seats.findByZoneIdAndActiveTrueOrderByLabel(1L); // 열람실 10석(예약 규칙을 거치지 않고 직접 기록을 넣는다)
        LocalDate day = LocalDate.of(2026, 11, 8);
        for (int i = 0; i < 3; i++) {
            pastSlot(m, zs.get(i).getId(), day.atTime(14, 0));
        }
        for (int i = 0; i < 5; i++) {
            pastSlot(m, zs.get(i).getId(), day.atTime(15, 0));
        }
        LocalDateTime now = LocalDateTime.of(2026, 11, 9, 12, 30);

        OccupancyService.BackfillResult first = occupancy.backfill(now);

        assertThat(first.hourly()).isGreaterThan(0);
        assertThat(first.daily()).isGreaterThan(0);
        OccupancyService.Hourly h14 = occupancy.hourly(1L, day).stream()
                .filter(h -> h.hour().getHour() == 14).findFirst().orElseThrow();
        assertThat(h14.occupied()).isEqualTo(3);
        assertThat(h14.capacity()).isEqualTo(10);
        OccupancyService.Daily d = occupancy.daily(1L, day, day).get(0);
        assertThat(d.peakHour()).isEqualTo(15);
        assertThat(d.peakRate()).isEqualTo(50.0);
        // 48시간 창에 일부 시간만 들어오는 11/7은 시간별이 모자라 일별로 만들지 않는다
        assertThat(occupancy.daily(1L, day.minusDays(1), day.minusDays(1))).isEmpty();

        // 다시 돌려도 새로 채울 것이 없고 행이 늘지 않는다
        long hourlyRows = count("SELECT COUNT(*) FROM occupancy_hourly");
        OccupancyService.BackfillResult second = occupancy.backfill(now);
        assertThat(second.hourly()).isZero();
        assertThat(second.daily()).isZero();
        assertThat(count("SELECT COUNT(*) FROM occupancy_hourly")).isEqualTo(hourlyRows);

        // 한 시간만 지워지면 그 시간만 다시 채워진다
        jdbc.update("DELETE FROM occupancy_hourly WHERE zone_id = 1 AND stat_hour = ?", day.atTime(14, 0));
        OccupancyService.BackfillResult third = occupancy.backfill(now);
        assertThat(third.hourly()).isEqualTo(1);
        assertThat(third.daily()).isZero();
        assertThat(count("SELECT occupied FROM occupancy_hourly WHERE zone_id = 1 AND stat_hour = ?",
                day.atTime(14, 0))).isEqualTo(3);
    }
}
