package com.ozo.contentforge.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.TenantId;

/**
 * Base class for everything that belongs to one brand. Hibernate fills {@code brandId} from {@link BrandContext} on
 * insert and restricts every query to the current brand, so services never pass the brand around by hand.
 * Rows saved outside a brand would get {@link BrandContext#NO_BRAND}; a database check ({@code brand_id > 0}) refuses them.
 */
@MappedSuperclass
@Getter
public abstract class BrandOwnedEntity {

    @TenantId
    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;
}
