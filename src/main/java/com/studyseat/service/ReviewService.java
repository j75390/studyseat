package com.studyseat.service;

import com.studyseat.domain.Branch;
import com.studyseat.domain.Member;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Review;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.ReviewRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    public record ReviewView(Review review, String writer, String branchName) {
    }

    private final ReviewRepository reviews;
    private final ReservationRepository reservations;
    private final MemberRepository members;
    private final SeatRepository seats;
    private final ZoneRepository zones;
    private final BranchRepository branches;

    public ReviewService(ReviewRepository reviews, ReservationRepository reservations, MemberRepository members,
                         SeatRepository seats, ZoneRepository zones, BranchRepository branches) {
        this.reviews = reviews;
        this.reservations = reservations;
        this.members = members;
        this.seats = seats;
        this.zones = zones;
        this.branches = branches;
    }

    @Transactional
    public void write(Long memberId, Long reservationId, int rating, String content, LocalDateTime now) {
        Reservation r = reservations.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다"));
        if (!r.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인의 예약이 아닙니다");
        }
        if (r.getStatus() != ReservationStatus.DONE) {
            throw new IllegalStateException("이용을 마친 예약에만 후기를 쓸 수 있습니다");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("별점은 1~5점입니다");
        }
        if (content == null || content.isBlank() || content.length() > 500) {
            throw new IllegalArgumentException("후기는 1~500자로 써 주세요");
        }
        try {
            reviews.saveAndFlush(new Review(reservationId, memberId, rating, content.trim(), now));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("이미 후기를 쓴 예약입니다");
        }
    }

    /** branchId가 있으면 그 지점 후기만, 없으면 전체 */
    @Transactional(readOnly = true)
    public List<ReviewView> list(Long branchIdOrNull) {
        List<Review> all = reviews.findAllByOrderByIdDesc();
        Set<Long> memberIds = all.stream().map(Review::getMemberId).collect(Collectors.toSet());
        Map<Long, String> names = members.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        Map<Long, Reservation> rsv = reservations.findAllById(
                all.stream().map(Review::getReservationId).toList()).stream()
                .collect(Collectors.toMap(Reservation::getId, Function.identity()));
        Map<Long, Seat> seatMap = seats.findAllById(rsv.values().stream().map(Reservation::getSeatId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Seat::getId, Function.identity()));
        Map<Long, Zone> zoneMap = zones.findAllById(seatMap.values().stream().map(Seat::getZoneId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Zone::getId, Function.identity()));
        Map<Long, Branch> branchMap = branches.findAllById(zoneMap.values().stream().map(Zone::getBranchId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Branch::getId, Function.identity()));
        return all.stream().map(rv -> {
            Reservation r = rsv.get(rv.getReservationId());
            Branch b = branchMap.get(zoneMap.get(seatMap.get(r.getSeatId()).getZoneId()).getBranchId());
            return new ReviewView(rv, mask(names.getOrDefault(rv.getMemberId(), "회원")), b.getName());
        }).filter(v -> branchIdOrNull == null || branchName(branchMap, branchIdOrNull).equals(v.branchName())).toList();
    }

    private static String branchName(Map<Long, Branch> map, Long id) {
        Branch b = map.get(id);
        return b == null ? "" : b.getName();
    }

    @Transactional(readOnly = true)
    public double averageRating(Long branchIdOrNull) {
        return list(branchIdOrNull).stream().mapToInt(v -> v.review().getRating()).average().orElse(0);
    }

    private static String mask(String name) {
        if (name.length() <= 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1); // 김지우 → 김*우
    }
}
