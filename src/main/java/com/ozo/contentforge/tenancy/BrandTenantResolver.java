package com.ozo.contentforge.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells Hibernate which brand the current session belongs to. Entities extending {@link BrandOwnedEntity} are then
 * filtered by {@code brand_id} on every query and stamped with it on insert.
 */
@Component
public class BrandTenantResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return BrandContext.tenantId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // Spring opens its own sessions; background jobs switch brand between sessions on the same thread
        return false;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return BrandContext.SYSTEM.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
