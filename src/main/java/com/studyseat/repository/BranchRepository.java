package com.studyseat.repository;

import com.studyseat.domain.Branch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    List<Branch> findAllByOrderById();
}
