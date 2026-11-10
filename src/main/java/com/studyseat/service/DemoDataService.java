package com.studyseat.service;

import com.studyseat.domain.Branch;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체험용 데이터. 지점이 수천 개(좌석 수만 석)라 JPA 개별 저장 대신 JDBC 배치·SQL 집합 연산으로 만든다.
 * - 오늘 [지금-2시간, 지금+4시간] 구간의 예약: 지도의 "지금 잔여석"이 현실처럼 보이게 한다.
 *   이미 끝난 시간은 DONE, 지금 시간은 CHECKED_IN, 앞으로는 BOOKED.
 * - 지난 3일의 시간별·일별 혼잡도 통계: 예약 기록 없이 시간대 곡선으로 합성한다(데모용).
 */
@Service
public class DemoDataService {

    private static final int BEFORE_HOURS = 2;
    private static final int AFTER_HOURS = 4;

    private final ZoneRepository zones;
    private final SeatRepository seats;
    private final BranchRepository branches;
    private final JdbcTemplate jdbc;
    private final OccupancyService occupancy;

    public DemoDataService(ZoneRepository zones, SeatRepository seats, BranchRepository branches, JdbcTemplate jdbc,
                           OccupancyService occupancy) {
        this.zones = zones;
        this.seats = seats;
        this.branches = branches;
        this.jdbc = jdbc;
        this.occupancy = occupancy;
    }

    /** 시간대별 붐비는 정도(0~1): 낮 3시 전후와 저녁 8시 전후가 가장 붐빈다. 새벽은 한산. */
    static double curve(int hour) {
        double base = hour < 7 ? 0.06 : 0.18;
        return base + 0.5 * Math.exp(-Math.pow(hour - 15, 2) / 9.0) + 0.38 * Math.exp(-Math.pow(hour - 20, 2) / 7.0);
    }

    @Transactional
    public void seed(Long visitorId, LocalDateTime now) {
        seedTodayBookings(visitorId, now);
        seedPastStats(now);
        seedReviews(visitorId, now);
    }

    private void seedTodayBookings(Long visitorId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDateTime hourNow = now.withMinute(0).withSecond(0).withNano(0);
        Map<Long, Branch> branchById = branches.findAll().stream().collect(Collectors.toMap(Branch::getId, b -> b));
        Map<Long, List<Seat>> seatsByZone = seats.findAll().stream().filter(Seat::isActive)
                .collect(Collectors.groupingBy(Seat::getZoneId));
        List<Object[]> rows = new ArrayList<>();
        int counter = 0;
        for (Zone z : zones.findAllByOrderById()) {
            Branch b = branchById.get(z.getBranchId());
            List<Seat> zoneSeats = new ArrayList<>(seatsByZone.getOrDefault(z.getId(), List.of()));
            if (zoneSeats.isEmpty()) {
                continue;
            }
            Random rnd = new Random(today.toEpochDay() * 131 + z.getId());
            double popularity = 0.7 + 0.6 * ((b.getId() * 37) % 100) / 100.0; // 지점마다 붐비는 정도가 다르다
            for (int h = hourNow.getHour() - BEFORE_HOURS; h <= hourNow.getHour() + AFTER_HOURS; h++) {
                if (h < 0 || h > 23 || !b.isOpenAt(h)) {
                    continue;
                }
                LocalDateTime start = today.atTime(h, 0);
                LocalDateTime end = start.plusHours(1);
                double fraction = curve(h) * popularity + (rnd.nextDouble() - 0.5) * 0.16;
                int count = (int) Math.round(Math.min(0.96, Math.max(0.0, fraction)) * zoneSeats.size());
                Collections.shuffle(zoneSeats, rnd);
                String status = end.compareTo(hourNow) <= 0 ? "DONE" : (start.compareTo(hourNow) <= 0 ? "CHECKED_IN" : "BOOKED");
                for (int i = 0; i < count; i++) {
                    rows.add(new Object[] {visitorId, zoneSeats.get(i).getId(), Timestamp.valueOf(start),
                            Timestamp.valueOf(end), status, z.getPricePointPerHr(), String.format("9%06d", counter++),
                            "BOOKED".equals(status) ? null : Timestamp.valueOf(start),
                            "DONE".equals(status) ? Timestamp.valueOf(end) : null, Timestamp.valueOf(start.minusDays(1))});
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO reservation (member_id, seat_id, start_at, end_at, status, used_point, code, "
                + "checked_in_at, checked_out_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        // 데모 예약은 모두 1시간이라 슬롯 = 시작 시각 한 칸
        jdbc.update("INSERT INTO reservation_slot (reservation_id, seat_id, slot_start) "
                + "SELECT id, seat_id, start_at FROM reservation WHERE member_id = ?", visitorId);
    }

    /** 지난 3일의 시간별 통계를 시간대 곡선으로 합성하고(지점·구역마다 약간씩 다르게) 일별 통계를 만든다 */
    private void seedPastStats(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        for (int back = 3; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            for (int h = 0; h < 24; h++) {
                jdbc.update(
                        "INSERT INTO occupancy_hourly (zone_id, stat_hour, occupied, capacity) "
                                + "SELECT z.id, ?, "
                                + "  CAST(ROUND(c.cap * LEAST(0.96, GREATEST(0.02, ? * (0.7 + 0.6 * MOD(b.id * 37, 100) / 100.0) "
                                + "    + (RAND() - 0.5) * 0.16))) AS INT), c.cap "
                                + "FROM zone z JOIN branch b ON b.id = z.branch_id "
                                + "JOIN (SELECT zone_id, COUNT(*) AS cap FROM seat WHERE active = TRUE GROUP BY zone_id) c ON c.zone_id = z.id "
                                + "WHERE b.open_hour <= ? AND ? < b.close_hour",
                        day.atTime(h, 0), curve(h), h, h);
            }
            occupancy.fillDaily(day);
        }
    }

    /**
     * 후기 데모: 서로 다른 작성자 12명이 여러 지점(대전 포함)에 남긴 후기를 만든다.
     * 후기가 붙는 이용 완료 예약의 주인도 그 작성자로 바꿔 데이터가 어긋나지 않게 한다.
     * (작성자 계정은 로그인할 수 없는 값을 비밀번호로 둔다.)
     */
    private void seedReviews(Long visitorId, LocalDateTime now) {
        String[] names = {"김지우", "이서연", "박민준", "최하윤", "정도현", "강수아", "조예준", "윤채원", "장지호", "임서윤", "한지민", "오세훈"};
        List<Long> writers = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            jdbc.update("INSERT INTO member (login_id, password, name, phone, role, created_at) VALUES (?, '!', ?, NULL, 'MEMBER', ?)",
                    String.format("reviewer%02d", i + 1), names[i], Timestamp.valueOf(now.minusDays(30 + i)));
            writers.add(jdbc.queryForObject("SELECT id FROM member WHERE login_id = ?", Long.class, String.format("reviewer%02d", i + 1)));
        }
        String[] texts = {
                "조용해서 집중이 잘 돼요. 좌석 간격도 넉넉하고 콘센트도 자리마다 있어요.",
                "저녁 시간엔 좀 붐비지만 예약해 두니 자리 걱정 없이 올 수 있어서 좋아요.",
                "스터디룸 방음이 잘 돼서 팀 스터디하기 좋았습니다. 화이트보드도 깔끔해요.",
                "프리미엄 부스는 의자가 편하고 파티션이 높아서 시험 기간에 딱입니다.",
                "입실 QR이 간단하고, 연장도 앱에서 바로 돼서 편했어요.",
                "24시간이라 밤샘 공부할 때 정말 든든해요. 샤워실도 깨끗합니다.",
                "역에서 가까워서 약속 전후로 잠깐 이용하기 좋아요.",
                "에어컨 온도가 적당하고 소음이 거의 없어서 오래 앉아 있어도 편해요.",
                "도면에서 창가 자리를 골라 예약할 수 있어서 좋았어요. 햇빛이 잘 들어요.",
                "시험 기간엔 자리가 금방 차서 미리 예약하는 게 필수예요. 앱이 편합니다.",
                "무료 커피가 맛있고 사장님이 친절해요. 단골 됐어요.",
                "프린터가 있어서 과제 출력하기 좋았어요. 가격도 부담 없습니다.",
                "조금 좁지만 가성비 좋아요. 평일 낮에는 한적합니다.",
                "스터디룸 예약 후 팀원들과 시간 맞춰 입실했는데 문제없이 잘 됐어요."};
        int[] ratings = {5, 4, 5, 5, 4, 5, 4, 5, 4, 4, 5, 4, 3, 5};

        // 전국에서 고르게 + 표본 지역인 대전에서 더 많이
        List<Long> any = jdbc.queryForList("SELECT id FROM reservation WHERE member_id = ? AND status = 'DONE' ORDER BY id",
                Long.class, visitorId);
        List<Long> daejeon = jdbc.queryForList(
                "SELECT r.id FROM reservation r JOIN seat s ON s.id = r.seat_id JOIN zone z ON z.id = s.zone_id "
                        + "JOIN branch b ON b.id = z.branch_id WHERE r.member_id = ? AND r.status = 'DONE' "
                        + "AND b.address LIKE '대전%' ORDER BY r.id", Long.class, visitorId);
        List<Long> picked = new ArrayList<>();
        for (int i = 0; i < 28 && !any.isEmpty(); i++) {
            picked.add(any.get((int) ((long) i * any.size() / 28)));
        }
        for (int i = 0; i < 20 && !daejeon.isEmpty(); i++) {
            picked.add(daejeon.get((int) ((long) i * daejeon.size() / 20)));
        }
        int n = 0;
        for (Long rid : new java.util.LinkedHashSet<>(picked)) {
            Long writer = writers.get(n % writers.size());
            jdbc.update("UPDATE reservation SET member_id = ? WHERE id = ?", writer, rid);
            jdbc.update("INSERT INTO review (reservation_id, member_id, rating, content, created_at) VALUES (?, ?, ?, ?, ?)",
                    rid, writer, ratings[(n * 5) % ratings.length], texts[(n * 3) % texts.length],
                    Timestamp.valueOf(now.minusMinutes(25L * n + 7)));
            n++;
        }
    }}
