package com.studyseat.repository;

import com.studyseat.domain.Review;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByOrderByIdDesc();

    boolean existsByReservationId(Long reservationId);
}
