package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.BrandSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandSubscriptionRepository extends JpaRepository<BrandSubscriptionEntity, Long> {
    Optional<BrandSubscriptionEntity> findFirstByBrandIdAndStatusOrderByIdDesc(Long brandId, String status);

    List<BrandSubscriptionEntity> findByBrandIdAndStatus(Long brandId, String status);
}
