package com.studyseat.service;

import com.studyseat.domain.Branch;
import com.studyseat.domain.PlanObject;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationSlot;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.PlanObjectRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.ReservationSlotRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지점 탐색(지도·목록), 지점 상세, 날짜·시간별 도면 현황, 내 지점. */
@Service
public class BranchService {

    /** FREE 선택 가능 · TAKEN 예약됨 · MINE 내 예약 · INACTIVE 이용 불가(꺼짐) · FIXED 고정석 · RESTRICTED 성별 전용이라 내가 못 쓰는 자리 */
    public enum SeatState { FREE, TAKEN, MINE, INACTIVE, FIXED, RESTRICTED }

    /** 지도·목록 카드 한 장 */
    public record BranchCard(Branch branch, int totalSeats, int freeNow, int busyPercent, boolean openNow,
                             int minPrice, boolean favorite) {
        public String level() {
            if (!openNow) {
                return "CLOSED";
            }
            return busyPercent >= 85 ? "FULL" : busyPercent >= 60 ? "BUSY" : "OK";
        }

        /** 주소의 첫 단어(시·도 약칭) */
        public String sido() {
            return branch.getAddress().split(" ")[0];
        }

        /** 주소의 둘째 단어(시·군·구) */
        public String sgg() {
            String[] p = branch.getAddress().split(" ");
            return p.length > 1 ? p[1] : "";
        }

        BranchCard withFavorite(boolean fav) {
            return new BranchCard(branch, totalSeats, freeNow, busyPercent, openNow, minPrice, fav);
        }
    }

    public record ZoneInfo(Zone zone, int seatCount, int freeNow) {
    }

    public record HourRate(int hour, int percent) {
    }

    public record Detail(BranchCard card, List<ZoneInfo> zones, List<String> amenities, List<HourRate> popularity) {
    }

    /** endingSoon: 선택한 시간 바로 전까지 다른 사람이 쓰다가 그 시간에 비는 "퇴실예정석" */
    public record SeatView(Seat seat, Zone zone, SeatState state, boolean endingSoon) {
    }

    public record PlanView(Branch branch, List<Zone> zones, List<SeatView> seats, List<PlanObject> objects,
                           int freeCount, int totalCount) {
    }

    private record Cached(LocalDateTime hour, long at, List<BranchCard> cards) {
    }

    private static final long CACHE_MILLIS = 20_000;

    private final BranchRepository branches;
    private final ZoneRepository zones;
    private final SeatRepository seats;
    private final PlanObjectRepository objects;
    private final ReservationSlotRepository slots;
    private final ReservationRepository reservations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MemberRepository members;
    private volatile Cached cache;

    public BranchService(BranchRepository branches, ZoneRepository zones, SeatRepository seats,
                         PlanObjectRepository objects, ReservationSlotRepository slots,
                         ReservationRepository reservations, JdbcTemplate jdbc, Clock clock,
                         MemberRepository members) {
        this.members = members;
        this.branches = branches;
        this.zones = zones;
        this.seats = seats;
        this.objects = objects;
        this.slots = slots;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 모든 지점 카드. 지점이 수천 개라 SQL 집계 한 번으로 계산하고, 실시간 요청이면 20초 동안 결과를 재사용한다.
     * (시험처럼 현재 시각과 동떨어진 시각으로 부르면 캐시를 쓰지 않는다.)
     */
    @Transactional(readOnly = true)
    public List<BranchCard> list(LocalDateTime now, Long memberIdOrNull) {
        List<BranchCard> base = baseCards(now);
        if (memberIdOrNull == null) {
            return base;
        }
        Set<Long> fav = favoriteIds(memberIdOrNull);
        if (fav.isEmpty()) {
            return base;
        }
        return base.stream().map(c -> fav.contains(c.branch().getId()) ? c.withFavorite(true) : c).toList();
    }

    private List<BranchCard> baseCards(LocalDateTime now) {
        LocalDateTime hour = now.withMinute(0).withSecond(0).withNano(0);
        boolean live = Math.abs(Duration.between(now, LocalDateTime.now(clock)).toMinutes()) < 2;
        if (live) {
            Cached c = cache;
            if (c != null && c.hour().equals(hour) && System.currentTimeMillis() - c.at() < CACHE_MILLIS) {
                return c.cards();
            }
        }
        List<BranchCard> cards = compute(hour, now.getHour(), null);
        if (live) {
            cache = new Cached(hour, System.currentTimeMillis(), cards);
        }
        return cards;
    }

    /** 활성 좌석 수·지금 시간 예약된 좌석 수·최저 요금을 지점별로 한 번에 집계한다. branchIdOrNull이면 한 지점만. */
    private List<BranchCard> compute(LocalDateTime hour, int hourOfDay, Long branchIdOrNull) {
        Map<Long, int[]> stats = new HashMap<>();
        String sql = "SELECT z.branch_id, COUNT(s.id), SUM(CASE WHEN rs.id IS NULL THEN 0 ELSE 1 END), MIN(z.price_point_per_hr) "
                + "FROM zone z JOIN seat s ON s.zone_id = z.id AND s.active = TRUE AND s.fixed_seat = FALSE "
                + "LEFT JOIN reservation_slot rs ON rs.seat_id = s.id AND rs.slot_start = ? "
                + (branchIdOrNull == null ? "" : "WHERE z.branch_id = ? ")
                + "GROUP BY z.branch_id";
        Object[] args = branchIdOrNull == null ? new Object[] {hour} : new Object[] {hour, branchIdOrNull};
        jdbc.query(sql, rs -> {
            stats.put(rs.getLong(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
        }, args);

        List<Branch> list = branchIdOrNull == null ? branches.findAllByOrderById()
                : branches.findById(branchIdOrNull).map(List::of).orElse(List.of());
        List<BranchCard> cards = new ArrayList<>(list.size());
        for (Branch b : list) {
            int[] st = stats.getOrDefault(b.getId(), new int[] {0, 0, 0});
            int total = st[0];
            int busy = st[1];
            boolean open = b.isOpenAt(hourOfDay);
            int busyPct = total == 0 ? 0 : (int) Math.round(busy * 100.0 / total);
            cards.add(new BranchCard(b, total, open ? total - busy : 0, open ? busyPct : 0, open, st[2], false));
        }
        return cards;
    }

    @Transactional(readOnly = true)
    public Detail detail(Long branchId, LocalDateTime now, Long memberIdOrNull) {
        LocalDateTime hour = now.withMinute(0).withSecond(0).withNano(0);
        BranchCard card = compute(hour, now.getHour(), branchId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지점을 찾을 수 없습니다"));
        if (memberIdOrNull != null && favoriteIds(memberIdOrNull).contains(branchId)) {
            card = card.withFavorite(true);
        }
        List<Zone> zoneList = zones.findByBranchIdOrderById(branchId);
        List<Seat> allSeats = seats.findByZoneIdInOrderByLabel(zoneList.stream().map(Zone::getId).toList());
        Set<Long> busy = slots.findBySeatIdInAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                        allSeats.stream().map(Seat::getId).toList(), hour, hour.plusHours(1))
                .stream().map(ReservationSlot::getSeatId).collect(Collectors.toSet());
        List<ZoneInfo> zoneInfos = new ArrayList<>();
        for (Zone z : zoneList) {
            List<Seat> zs = allSeats.stream().filter(s -> s.getZoneId().equals(z.getId()) && s.isActive()).toList();
            int free = card.openNow() ? (int) zs.stream().filter(s -> !busy.contains(s.getId())).count() : 0;
            zoneInfos.add(new ZoneInfo(z, zs.size(), free));
        }
        List<String> amenities = card.branch().getAmenities() == null ? List.of()
                : Arrays.stream(card.branch().getAmenities().split(",")).map(String::trim).toList();
        return new Detail(card, zoneInfos, amenities, popularity(branchId, now));
    }

    /** 최근 14일 시간대별 평균 점유율(%) — "언제 한가한지" 보여주는 막대 */
    private List<HourRate> popularity(Long branchId, LocalDateTime now) {
        return jdbc.query(
                "SELECT EXTRACT(HOUR FROM h.stat_hour) AS hr, SUM(h.occupied) AS occ, SUM(h.capacity) AS cap "
                        + "FROM occupancy_hourly h JOIN zone z ON z.id = h.zone_id "
                        + "WHERE z.branch_id = ? AND h.stat_hour >= ? GROUP BY EXTRACT(HOUR FROM h.stat_hour) ORDER BY hr",
                (rs, i) -> {
                    long cap = rs.getLong(3);
                    return new HourRate(rs.getInt(1), cap == 0 ? 0 : (int) Math.round(rs.getLong(2) * 100.0 / cap));
                }, branchId, now.minusDays(14));
    }

    /** [startHour, startHour+hours) 구간에 각 좌석이 비어 있는지 계산한 도면 */
    @Transactional(readOnly = true)
    public PlanView plan(Long branchId, LocalDate date, int startHour, int hours, Long memberIdOrNull) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> new IllegalArgumentException("지점을 찾을 수 없습니다"));
        List<Zone> zoneList = zones.findByBranchIdOrderById(branchId);
        Map<Long, Zone> zoneById = zoneList.stream().collect(Collectors.toMap(Zone::getId, z -> z));
        List<Seat> seatList = seats.findByZoneIdInOrderByLabel(zoneById.keySet());

        LocalDateTime from = date.atTime(startHour, 0);
        LocalDateTime to = from.plusHours(hours);
        Map<Long, Long> takenBy = new HashMap<>(); // seatId -> reservationId
        for (ReservationSlot s : slots.findBySeatIdInAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                seatList.stream().map(Seat::getId).toList(), from, to)) {
            takenBy.put(s.getSeatId(), s.getReservationId());
        }
        Set<Long> mine = new HashSet<>();
        String viewerGender = null;
        if (memberIdOrNull != null) {
            for (Reservation r : reservations.findByMemberIdOrderByStartAtDesc(memberIdOrNull)) {
                if (r.getStatus() == ReservationStatus.BOOKED || r.getStatus() == ReservationStatus.CHECKED_IN) {
                    mine.add(r.getId());
                }
            }
            viewerGender = members.findById(memberIdOrNull).map(m -> m.getGender()).orElse(null);
        }
        // 선택한 시간 바로 전 시간에 쓰는 사람이 있는 좌석 = 그 시간에 비는 "퇴실예정석"
        Set<Long> justBefore = slots.findBySeatIdInAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                        seatList.stream().map(Seat::getId).toList(), from.minusHours(1), from)
                .stream().map(ReservationSlot::getSeatId).collect(Collectors.toSet());
        List<SeatView> views = new ArrayList<>();
        int free = 0;
        int total = 0;
        for (Seat s : seatList) {
            SeatState st;
            boolean ending = false;
            if (!s.isActive()) {
                st = SeatState.INACTIVE;
            } else if (s.isFixedSeat()) {
                st = SeatState.FIXED; // 월 이용권 전용이라 시간권 좌석 수에서 뺀다
            } else {
                total++;
                boolean restricted = memberIdOrNull != null && !"A".equals(s.getGender()) && !s.getGender().equals(viewerGender);
                if (takenBy.containsKey(s.getId())) {
                    st = mine.contains(takenBy.get(s.getId())) ? SeatState.MINE : SeatState.TAKEN;
                } else if (restricted) {
                    st = SeatState.RESTRICTED;
                } else {
                    st = SeatState.FREE;
                    free++;
                    ending = justBefore.contains(s.getId());
                }
            }
            views.add(new SeatView(s, zoneById.get(s.getZoneId()), st, ending));
        }
        return new PlanView(branch, zoneList, views, objects.findByBranchId(branchId), free, total);
    }

    @Transactional(readOnly = true)
    public Set<Long> favoriteIds(Long memberId) {
        return new HashSet<>(jdbc.queryForList("SELECT branch_id FROM favorite_branch WHERE member_id = ?",
                Long.class, memberId));
    }

    /** 내 지점 등록/해제. 켜졌으면 true */
    @Transactional
    public boolean toggleFavorite(Long memberId, Long branchId) {
        if (branches.findById(branchId).isEmpty()) {
            throw new IllegalArgumentException("지점을 찾을 수 없습니다");
        }
        int removed = jdbc.update("DELETE FROM favorite_branch WHERE member_id = ? AND branch_id = ?", memberId, branchId);
        if (removed > 0) {
            return false;
        }
        jdbc.update("INSERT INTO favorite_branch (member_id, branch_id) VALUES (?, ?)", memberId, branchId);
        return true;
    }
}
