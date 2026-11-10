package com.studyseat.repository;

import com.studyseat.domain.Seat;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByZoneIdOrderByLabel(Long zoneId);

    List<Seat> findByZoneIdAndActiveTrueOrderByLabel(Long zoneId);

    List<Seat> findByZoneIdInOrderByLabel(Collection<Long> zoneIds);

    long countByZoneIdAndActiveTrue(Long zoneId);
}
