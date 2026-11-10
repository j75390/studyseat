package com.studyseat.repository;

import com.studyseat.domain.CafePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CafePolicyRepository extends JpaRepository<CafePolicy, Long> {
}
