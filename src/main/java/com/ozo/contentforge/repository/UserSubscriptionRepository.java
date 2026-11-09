package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.UserSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscriptionEntity, Long> {
    
    @Query("SELECT s FROM UserSubscriptionEntity s WHERE s.userId = :userId AND s.status = 'ACTIVE' ORDER BY s.id DESC")
    List<UserSubscriptionEntity> findActiveSubscriptionsByUserId(@Param("userId") Long userId);

    default Optional<UserSubscriptionEntity> findFirstActiveByUserId(Long userId) {
        List<UserSubscriptionEntity> list = findActiveSubscriptionsByUserId(userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    List<UserSubscriptionEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}

