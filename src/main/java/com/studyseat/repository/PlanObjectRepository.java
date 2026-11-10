package com.studyseat.repository;

import com.studyseat.domain.PlanObject;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanObjectRepository extends JpaRepository<PlanObject, Long> {
    List<PlanObject> findByBranchId(Long branchId);
}
