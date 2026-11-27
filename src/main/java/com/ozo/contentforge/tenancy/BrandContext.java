package com.ozo.contentforge.tenancy;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The brand (client workspace) the current thread works for.
 *
 * Every brand-owned entity carries a {@code brand_id}; Hibernate adds {@code brand_id = <current brand>} to every query
 * and fills it on insert (see {@link BrandTenantResolver}). A request sets the brand from the {@code X-Brand-Id} header;
 * background work (schedulers, async jobs) sets it explicitly with {@link #runAs}.
 *
 * "System" mode lifts the filter so a scheduler can find due work across all brands. It must only be used for such
 * cross-brand lookups, never for creating content.
 */
public final class BrandContext {

    /** Tenant id used when no brand is set: matches no rows, so nothing leaks. */
    public static final Long NO_BRAND = 0L;
    /** Tenant id that Hibernate treats as root (no brand filter). */
    public static final Long SYSTEM = -1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM_MODE = new ThreadLocal<>();

    private BrandContext() {
    }

    public static Optional<Long> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current brand; fails when the code runs outside a brand. */
    public static Long require() {
        Long id = CURRENT.get();
        if (id == null) throw new IllegalStateException("No brand selected for this request");
        return id;
    }

    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM_MODE.get());
    }

    /** The id Hibernate filters on. */
    static Long tenantId() {
        if (isSystem()) return SYSTEM;
        Long id = CURRENT.get();
        return id != null ? id : NO_BRAND;
    }

    public static void set(Long brandId) {
        CURRENT.set(brandId);
    }

    public static void clear() {
        CURRENT.remove();
        SYSTEM_MODE.remove();
    }

    public static void runAs(Long brandId, Runnable work) {
        callAs(brandId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAs(Long brandId, Callable<T> work) {
        Long previousBrand = CURRENT.get();
        Boolean previousSystem = SYSTEM_MODE.get();
        CURRENT.set(brandId);
        SYSTEM_MODE.remove();
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            restore(previousBrand, previousSystem);
        }
    }

    /** Runs a cross-brand lookup (no brand filter). */
    public static <T> T callAsSystem(Callable<T> work) {
        Long previousBrand = CURRENT.get();
        Boolean previousSystem = SYSTEM_MODE.get();
        CURRENT.remove();
        SYSTEM_MODE.set(true);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            restore(previousBrand, previousSystem);
        }
    }

    /** Captures the caller's brand so work handed to another thread runs for the same brand. */
    public static Runnable wrap(Runnable work) {
        Long brandId = CURRENT.get();
        if (brandId == null) return work;
        return () -> runAs(brandId, work);
    }

    private static void restore(Long brand, Boolean system) {
        if (brand != null) CURRENT.set(brand);
        else CURRENT.remove();
        if (system != null) SYSTEM_MODE.set(system);
        else SYSTEM_MODE.remove();
    }
}
