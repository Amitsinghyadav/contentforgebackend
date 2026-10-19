package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.CarouselSlideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CarouselSlideRepository extends JpaRepository<CarouselSlideEntity, Long> {
    List<CarouselSlideEntity> findByPostIdOrderBySlideNumberAsc(Long postId);
    Optional<CarouselSlideEntity> findByPostIdAndSlideNumber(Long postId, Integer slideNumber);
    void deleteByPostId(Long postId);
}
