package com.studyseat.service;

import com.studyseat.domain.Branch;
import com.studyseat.domain.CafePolicy;
import com.studyseat.domain.Member;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationSlot;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.CafePolicyRepository;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.ReservationSlotRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final int CHECKIN_EARLY_MIN = 10;
    private static final int MAX_EXTEND_HOURS = 2;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReservationRepository reservations;
    private final ReservationSlotRepository slots;
    private final SeatRepository seats;
    private final ZoneRepository zones;
    private final BranchRepository branches;
    private final CafePolicyRepository policies;
    private final MemberRepository members;
    private final PointService points;

    public ReservationService(ReservationRepository reservations, ReservationSlotRepository slots,
                              SeatRepository seats, ZoneRepository zones, BranchRepository branches,
                              CafePolicyRepository policies, MemberRepository members, PointService points) {
        this.reservations = reservations;
        this.slots = slots;
        this.seats = seats;
        this.zones = zones;
        this.branches = branches;
        this.policies = policies;
        this.members = members;
        this.points = points;
    }

    /**
     * 좌석 예약.
     * 1) 회원 행을 잠가 같은 회원의 동시 예약이 포인트를 이중으로 쓰지 못하게 하고
     * 2) 예약을 1시간 슬롯으로 쪼개 넣어 (seat_id, slot_start) 유니크 제약이 겹치는 예약을 DB 단에서 거부하게 한다.
     */
    @Transactional
    public Reservation reserve(Long memberId, Long seatId, LocalDateTime startAt, int hours, LocalDateTime now) {
        // 회원 락을 트랜잭션의 첫 문장으로 잡는다. MySQL 기본 격리(REPEATABLE READ)에서는 첫 일반 SELECT 때
        // 읽기 스냅샷이 만들어지므로, 락보다 먼저 다른 조회를 하면 앞 트랜잭션이 막 커밋한 사용 내역이 안 보여
        // 잔액 검증을 잘못 통과할 수 있다. (격리 수준도 READ COMMITTED로 맞춰 두었다 — application.yml)
        Member member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다"));
        CafePolicy policy = policy();
        Seat seat = seats.findById(seatId).filter(Seat::isActive)
                .orElseThrow(() -> new IllegalArgumentException("이용할 수 없는 좌석입니다"));
        checkSeatAllowed(seat, member);
        Zone zone = zones.findById(seat.getZoneId()).orElseThrow();
        Branch branch = branches.findById(zone.getBranchId()).orElseThrow();
        validate(startAt, hours, now, policy, branch);

        int price = hours * zone.getPricePointPerHr();
        Reservation reservation = reservations.save(
                new Reservation(memberId, seatId, startAt, startAt.plusHours(hours), price, newCode(), now));

        List<ReservationSlot> rows = new ArrayList<>();
        for (int i = 0; i < hours; i++) {
            rows.add(new ReservationSlot(reservation.getId(), seatId, startAt.plusHours(i)));
        }
        try {
            slots.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException e) {
            throw new SeatTakenException("이미 예약된 시간이 포함되어 있습니다");
        }
        points.use(memberId, reservation.getId(), price, now);
        return reservation;
    }

    /**
     * 이용 시간 연장(1~2시간, 예약당 1회). 바로 뒤 시간이 비어 있어야 하고, 포인트가 모자라지 않아야 한다.
     * 연장분은 원장에 EXTEND로 따로 남기며, 취소하면 예약 전체 사용 포인트가 환불된다.
     */
    @Transactional
    public Reservation extend(Long memberId, Long reservationId, int addHours, LocalDateTime now) {
        members.findByIdForUpdate(memberId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다"));
        Reservation r = lockOwned(memberId, reservationId);
        if (r.getStatus() != ReservationStatus.BOOKED && r.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("연장할 수 있는 예약이 아닙니다");
        }
        if (!now.isBefore(r.getEndAt())) {
            throw new IllegalStateException("이미 이용 시간이 끝났습니다");
        }
        if (points.hasRecord(r.getId(), "EXTEND")) {
            throw new IllegalStateException("연장은 예약당 1회만 가능합니다");
        }
        if (addHours < 1 || addHours > MAX_EXTEND_HOURS) {
            throw new IllegalArgumentException("연장은 1~" + MAX_EXTEND_HOURS + "시간까지 가능합니다");
        }
        CafePolicy policy = policy();
        if (r.hours() + addHours > policy.getMaxHoursPerRsv()) {
            throw new IllegalStateException("한 번에 최대 " + policy.getMaxHoursPerRsv() + "시간까지 이용할 수 있습니다");
        }
        Seat seat = seats.findById(r.getSeatId()).orElseThrow();
        Zone zone = zones.findById(seat.getZoneId()).orElseThrow();
        Branch branch = branches.findById(zone.getBranchId()).orElseThrow();
        LocalDateTime newEnd = r.getEndAt().plusHours(addHours);
        if (endHourOf(r.getStartAt(), newEnd) > branch.getCloseHour()) {
            throw new IllegalStateException("운영 시간(" + branch.getCloseHour() + "시)을 넘겨 연장할 수 없습니다");
        }
        List<ReservationSlot> rows = new ArrayList<>();
        for (int i = 0; i < addHours; i++) {
            rows.add(new ReservationSlot(r.getId(), r.getSeatId(), r.getEndAt().plusHours(i)));
        }
        try {
            slots.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException e) {
            throw new SeatTakenException("바로 뒤 시간에 다른 예약이 있어 연장할 수 없습니다");
        }
        int cost = addHours * zone.getPricePointPerHr();
        points.useFor(memberId, r.getId(), cost, "EXTEND", now);
        r.extend(addHours, cost);
        return r;
    }

    /** 회원 본인 취소: 시작 N분 전까지만. 슬롯을 지워 자리를 다시 열고 사용한 포인트를 환불한다. */
    @Transactional
    public void cancel(Long memberId, Long reservationId, LocalDateTime now) {
        Reservation r = lockOwned(memberId, reservationId);
        if (r.getStatus() != ReservationStatus.BOOKED) {
            throw new IllegalStateException("취소할 수 있는 예약이 아닙니다");
        }
        if (now.isAfter(r.getStartAt().minusMinutes(policy().getCancelBeforeMin()))) {
            throw new IllegalStateException("시작 " + policy().getCancelBeforeMin() + "분 전까지만 취소할 수 있습니다");
        }
        doCancel(r, now);
    }

    /** 관리자 강제 취소: 시간 제한 없이 취소하고 환불한다. */
    @Transactional
    public void forceCancel(Long reservationId, LocalDateTime now) {
        Reservation r = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다"));
        if (r.getStatus() != ReservationStatus.BOOKED && r.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("취소할 수 있는 예약이 아닙니다");
        }
        doCancel(r, now);
    }

    private void doCancel(Reservation r, LocalDateTime now) {
        slots.deleteByReservationId(r.getId());
        r.cancel();
        points.refund(r.getMemberId(), r.getId(), r.getUsedPoint(), now);
    }

    @Transactional
    public void checkIn(Long memberId, Long reservationId, LocalDateTime now) {
        Reservation r = lockOwned(memberId, reservationId);
        if (r.getStatus() != ReservationStatus.BOOKED) {
            throw new IllegalStateException("체크인할 수 있는 예약이 아닙니다");
        }
        LocalDateTime from = r.getStartAt().minusMinutes(CHECKIN_EARLY_MIN);
        LocalDateTime until = r.getStartAt().plusMinutes(policy().getNoshowGraceMin());
        if (now.isBefore(from)) {
            throw new IllegalStateException("체크인은 시작 " + CHECKIN_EARLY_MIN + "분 전부터 가능합니다");
        }
        if (now.isAfter(until)) {
            throw new IllegalStateException("체크인 시간이 지났습니다");
        }
        r.checkIn(now);
    }

    @Transactional
    public void checkOut(Long memberId, Long reservationId, LocalDateTime now) {
        Reservation r = lockOwned(memberId, reservationId);
        if (r.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("이용 중인 예약이 아닙니다");
        }
        finish(r, now);
    }

    /** 키오스크(입구 단말) 입실: 예약 코드로 본인 확인을 대신한다. */
    @Transactional
    public Reservation kioskCheckIn(String code, LocalDateTime now) {
        Reservation r = reservations.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new IllegalArgumentException("예약 코드를 찾을 수 없습니다"));
        checkIn(r.getMemberId(), r.getId(), now);
        return reservations.findById(r.getId()).orElseThrow();
    }

    /** 키오스크 퇴실 */
    @Transactional
    public Reservation kioskCheckOut(String code, LocalDateTime now) {
        Reservation r = reservations.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new IllegalArgumentException("예약 코드를 찾을 수 없습니다"));
        checkOut(r.getMemberId(), r.getId(), now);
        return reservations.findById(r.getId()).orElseThrow();
    }

    private void finish(Reservation r, LocalDateTime at) {
        r.done(at);
        points.earn(r.getMemberId(), r.getId(), r.hours() * policy().getEarnPointPerHr(), at);
    }

    /**
     * 주기 점검(스케줄러가 호출).
     * - 시작 후 유예시간 안에 체크인하지 않은 예약은 노쇼: 첫 1시간 슬롯은 유지(자리 비운 시간)하고 이후 슬롯만 풀며, 포인트는 환불하지 않는다.
     * - 이용 중인데 퇴실 버튼을 안 누른 예약은 종료 시각에 자동 종료 + 적립.
     * 여러 번 실행돼도 같은 결과가 나온다(상태·유니크 인덱스로 멱등).
     */
    @Transactional
    public int[] sweep(LocalDateTime now) {
        int noShow = 0;
        int done = 0;
        LocalDateTime cutoff = now.minusMinutes(policy().getNoshowGraceMin());
        for (Reservation r : reservations.findByStatusAndStartAtBefore(ReservationStatus.BOOKED, cutoff)) {
            Reservation locked = reservations.findByIdForUpdate(r.getId()).orElse(null);
            if (locked == null || locked.getStatus() != ReservationStatus.BOOKED) {
                continue;
            }
            locked.noShow();
            slots.deleteByReservationIdAndSlotStartGreaterThan(locked.getId(), locked.getStartAt());
            noShow++;
        }
        for (Reservation r : reservations.findByStatusAndEndAtLessThanEqual(ReservationStatus.CHECKED_IN, now)) {
            Reservation locked = reservations.findByIdForUpdate(r.getId()).orElse(null);
            if (locked == null || locked.getStatus() != ReservationStatus.CHECKED_IN) {
                continue;
            }
            finish(locked, locked.getEndAt());
            done++;
        }
        return new int[] {noShow, done};
    }

    @Transactional(readOnly = true)
    public List<Reservation> listForMember(Long memberId) {
        return reservations.findByMemberIdOrderByStartAtDesc(memberId);
    }

    @Transactional(readOnly = true)
    public Reservation get(Long memberId, Long reservationId) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다"));
        if (!r.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인의 예약이 아닙니다");
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<Reservation> recent() {
        return reservations.findTop100ByOrderByStartAtDesc();
    }

    private Reservation lockOwned(Long memberId, Long reservationId) {
        Reservation r = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다"));
        if (!r.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인의 예약이 아닙니다");
        }
        return r;
    }

    private String newCode() {
        for (int i = 0; i < 20; i++) {
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (!reservations.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("예약 코드를 만들지 못했습니다");
    }

    private CafePolicy policy() {
        return policies.findById(1L).orElseThrow(() -> new IllegalStateException("운영 정책이 없습니다"));
    }

    /** 고정석과 성별 전용 좌석 규칙. 도면에서는 선택할 수 없게 그리지만 서버가 한 번 더 확인한다. */
    static void checkSeatAllowed(Seat seat, Member member) {
        if (seat.isFixedSeat()) {
            throw new IllegalArgumentException("고정석은 월 이용권 회원 전용이라 시간권으로 예약할 수 없습니다");
        }
        String g = seat.getGender();
        if (!"A".equals(g) && !g.equals(member.getGender())) {
            String label = "F".equals(g) ? "여성" : "남성";
            throw new IllegalArgumentException(label + " 전용 좌석입니다"
                    + (member.getGender() == null ? " (회원 정보에 성별이 없어 예약할 수 없어요)" : ""));
        }
    }

    /** 끝 시각을 "시작한 날 기준 몇 시"로 환산 (자정이면 24, 그 이후는 운영 시간 밖) */
    static int endHourOf(LocalDateTime startAt, LocalDateTime endAt) {
        if (endAt.toLocalDate().equals(startAt.toLocalDate())) {
            return endAt.getHour();
        }
        if (endAt.toLocalDate().equals(startAt.toLocalDate().plusDays(1)) && endAt.getHour() == 0) {
            return 24;
        }
        return Integer.MAX_VALUE;
    }

    private void validate(LocalDateTime startAt, int hours, LocalDateTime now, CafePolicy policy, Branch branch) {
        if (hours < 1 || hours > policy.getMaxHoursPerRsv()) {
            throw new IllegalArgumentException("이용 시간은 1~" + policy.getMaxHoursPerRsv() + "시간입니다");
        }
        if (startAt.getMinute() != 0 || startAt.getSecond() != 0 || startAt.getNano() != 0) {
            throw new IllegalArgumentException("시작 시각은 정시여야 합니다");
        }
        if (startAt.isBefore(now.withMinute(0).withSecond(0).withNano(0))) {
            throw new IllegalArgumentException("지난 시간은 예약할 수 없습니다");
        }
        int endHour = endHourOf(startAt, startAt.plusHours(hours));
        if (startAt.getHour() < branch.getOpenHour() || endHour > branch.getCloseHour()) {
            throw new IllegalArgumentException(branch.getName() + " 운영 시간(" + branch.getOpenHour() + "~"
                    + branch.getCloseHour() + "시) 안에서만 예약할 수 있습니다");
        }
    }
}
