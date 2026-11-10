package com.studyseat.repository;

import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByMemberIdOrderByStartAtDesc(Long memberId);

    List<Reservation> findTop100ByOrderByStartAtDesc();

    boolean existsByCode(String code);

    java.util.Optional<Reservation> findByCode(String code);

    List<Reservation> findByStatusAndStartAtBefore(ReservationStatus status, LocalDateTime time);

    List<Reservation> findByStatusAndEndAtLessThanEqual(ReservationStatus status, LocalDateTime time);

    /** 취소·체크인 같은 상태 변경이 겹치지 않도록 예약 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);
}
