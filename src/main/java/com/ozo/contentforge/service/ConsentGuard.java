package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.tenancy.BrandContext;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/** Blocks work on a person's face or voice until the current brand has signed consent on record. */
@Component
public class ConsentGuard {

    private final BrandRepository brandRepository;

    public ConsentGuard(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public void requireFace() {
        if (!brand().hasFaceConsent()) {
            throw new IllegalStateException("This brand has no signed consent for using the person's face. Record it under Brand → Consent first.");
        }
    }

    public void requireVoice() {
        if (!brand().hasVoiceConsent()) {
            throw new IllegalStateException("This brand has no signed consent for cloning the person's voice. Record it under Brand → Consent first.");
        }
    }

    private BrandEntity brand() {
        return brandRepository.findById(BrandContext.require()).orElseThrow(() -> new NoSuchElementException("Brand not found"));
    }
}
