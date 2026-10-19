package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.ContentApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ContentApprovalRepository extends JpaRepository<ContentApprovalEntity, Long> {
    List<ContentApprovalEntity> findByPostIdOrderByDecidedAtDesc(Long postId);
    @Transactional
    void deleteByPostId(Long postId);
}
