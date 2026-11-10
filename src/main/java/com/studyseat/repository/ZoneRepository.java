package com.studyseat.repository;

import com.studyseat.domain.Zone;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<Zone, Long> {
    List<Zone> findAllByOrderById();

    List<Zone> findByBranchIdOrderById(Long branchId);
}
