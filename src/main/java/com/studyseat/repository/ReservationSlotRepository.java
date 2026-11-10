package com.studyseat.repository;

import com.studyseat.domain.ReservationSlot;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSlotRepository extends JpaRepository<ReservationSlot, Long> {

    List<ReservationSlot> findBySlotStartGreaterThanEqualAndSlotStartLessThan(LocalDateTime from, LocalDateTime to);

    /** 지정한 좌석들의 [from, to) 구간 슬롯 */
    List<ReservationSlot> findBySeatIdInAndSlotStartGreaterThanEqualAndSlotStartLessThan(
            Collection<Long> seatIds, LocalDateTime from, LocalDateTime to);

    void deleteByReservationId(Long reservationId);

    void deleteByReservationIdAndSlotStartGreaterThan(Long reservationId, LocalDateTime after);
}
