package com.ozo.contentforge.service.publishing;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.service.MetaIntegrationService;
import com.ozo.contentforge.tenancy.BrandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning: checks each brand's Instagram connection still works, so a broken one shows before a publish fails. */
@Component
public class MetaConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(MetaConnectionMonitor.class);

    private final BrandRepository brandRepository;
    private final MetaIntegrationService metaIntegrationService;

    public MetaConnectionMonitor(BrandRepository brandRepository, MetaIntegrationService metaIntegrationService) {
        this.brandRepository = brandRepository;
        this.metaIntegrationService = metaIntegrationService;
    }

    @Scheduled(cron = "${contentforge.meta.health-cron:0 30 5 * * ?}", zone = "Asia/Kolkata")
    public void checkAllBrands() {
        for (BrandEntity brand : brandRepository.findByActiveTrueOrderByNameAsc()) {
            try {
                BrandContext.runAs(brand.getId(), () -> {
                    Object status = metaIntegrationService.checkConnectionHealth().get("tokenStatus");
                    if ("INVALID".equals(status)) {
                        log.warn("Instagram connection of brand '{}' no longer works: reconnect it in Settings", brand.getName());
                    }
                });
            } catch (Exception e) {
                log.warn("Connection check failed for brand '{}': {}", brand.getName(), e.getMessage());
            }
        }
    }
}
