package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.BrandEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrandRepository extends JpaRepository<BrandEntity, Long> {
    List<BrandEntity> findAllByOrderByNameAsc();

    List<BrandEntity> findByActiveTrueOrderByNameAsc();
}
