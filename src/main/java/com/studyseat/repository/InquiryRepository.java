package com.studyseat.repository;

import com.studyseat.domain.Inquiry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
    List<Inquiry> findByMemberIdOrderByIdDesc(Long memberId);

    List<Inquiry> findAllByOrderByIdDesc();
}
