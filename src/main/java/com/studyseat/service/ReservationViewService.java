package com.studyseat.service;

import com.studyseat.domain.Branch;
import com.studyseat.domain.CafePolicy;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.CafePolicyRepository;
import com.studyseat.repository.PointLedgerRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.ReviewRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 화면에 보여 줄 예약 정보(지점·구역·좌석 이름과 가능한 버튼)를 한곳에서 계산한다. */
@Service
public class ReservationViewService {

    public record View(Reservation r, Branch branch, Zone zone, Seat seat, boolean canCancel, boolean canCheckIn,
                       boolean canCheckOut, boolean canExtend, boolean canReview, boolean inUse, boolean upcoming) {
        public String place() {
            return branch.getName() + " · " + zone.getName() + " " + seat.getLabel();
        }
    }

    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final ZoneRepository zones;
    private final BranchRepository branches;
    private final CafePolicyRepository policies;
    private final PointLedgerRepository ledger;
    private final ReviewRepository reviews;

    public ReservationViewService(ReservationRepository reservations, SeatRepository seats, ZoneRepository zones,
                                  BranchRepository branches, CafePolicyRepository policies,
                                  PointLedgerRepository ledger, ReviewRepository reviews) {
        this.reservations = reservations;
        this.seats = seats;
        this.zones = zones;
        this.branches = branches;
        this.policies = policies;
        this.ledger = ledger;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public List<View> mine(Long memberId, LocalDateTime now) {
        return build(reservations.findByMemberIdOrderByStartAtDesc(memberId), now);
    }

    @Transactional(readOnly = true)
    public View one(Long memberId, Long reservationId, LocalDateTime now) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다"));
        if (!r.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인의 예약이 아닙니다");
        }
        return build(List.of(r), now).get(0);
    }

    @Transactional(readOnly = true)
    public View byCode(String code, LocalDateTime now) {
        Reservation r = reservations.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new IllegalArgumentException("예약 코드를 찾을 수 없습니다"));
        return build(List.of(r), now).get(0);
    }

    @Transactional(readOnly = true)
    public List<View> recent(LocalDateTime now) {
        return build(reservations.findTop100ByOrderByStartAtDesc(), now);
    }

    private List<View> build(List<Reservation> list, LocalDateTime now) {
        CafePolicy policy = policies.findById(1L).orElseThrow();
        // 지점·좌석이 수만 개라 화면에 필요한 것만 id로 읽는다
        Map<Long, Seat> seatMap = seats.findAllById(list.stream().map(Reservation::getSeatId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Seat::getId, Function.identity()));
        Map<Long, Zone> zoneMap = zones.findAllById(seatMap.values().stream().map(Seat::getZoneId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Zone::getId, Function.identity()));
        Map<Long, Branch> branchMap = branches.findAllById(zoneMap.values().stream().map(Zone::getBranchId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Branch::getId, Function.identity()));
        return list.stream().map(r -> {
            Seat s = seatMap.get(r.getSeatId());
            Zone z = zoneMap.get(s.getZoneId());
            Branch b = branchMap.get(z.getBranchId());
            boolean booked = r.getStatus() == ReservationStatus.BOOKED;
            boolean using = r.getStatus() == ReservationStatus.CHECKED_IN;
            boolean canCancel = booked && !now.isAfter(r.getStartAt().minusMinutes(policy.getCancelBeforeMin()));
            boolean canCheckIn = booked && !now.isBefore(r.getStartAt().minusMinutes(10))
                    && !now.isAfter(r.getStartAt().plusMinutes(policy.getNoshowGraceMin()));
            boolean canExtend = (booked || using) && now.isBefore(r.getEndAt())
                    && r.hours() < policy.getMaxHoursPerRsv() && !ledger.existsByReservationIdAndReason(r.getId(), "EXTEND");
            boolean canReview = r.getStatus() == ReservationStatus.DONE && !reviews.existsByReservationId(r.getId());
            return new View(r, b, z, s, canCancel, canCheckIn, using, canExtend, canReview, using,
                    booked && now.isBefore(r.getEndAt()));
        }).toList();
    }
}
