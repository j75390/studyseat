package com.studyseat.repository;

import com.studyseat.domain.PointLedger;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {

    List<PointLedger> findByMemberIdOrderByIdDesc(Long memberId);

    @Query("select coalesce(sum(p.amount), 0) from PointLedger p where p.memberId = :memberId")
    long balance(@Param("memberId") Long memberId);

    boolean existsByReservationIdAndReason(Long reservationId, String reason);
}
