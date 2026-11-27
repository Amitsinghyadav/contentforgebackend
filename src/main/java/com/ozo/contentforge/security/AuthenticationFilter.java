package com.ozo.contentforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.service.security.JwtService;
import com.ozo.contentforge.tenancy.BrandContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every API call needs a signed-in, active team member. Admin endpoints need an admin. Calls that touch client content
 * also name the brand they work in with the {@code X-Brand-Id} header, which scopes all their data to that brand.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String BRAND_HEADER = "X-Brand-Id";

    /** Reachable without signing in. */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/media/s3" // media proxy used directly by <img>/<video> tags, keys are random UUIDs
    );

    /** Signed in, but not inside one brand (account, team-wide and admin screens). */
    private static final List<String> BRANDLESS_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/admin/",
            "/api/v1/workspaces",
            "/api/v1/plans",
            "/api/v1/team/"
    );

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final BrandRepository brandRepository;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(JwtService jwtService, UserRepository userRepository, BrandRepository brandRepository,
                                ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.brandRepository = brandRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Optional<UserEntity> user = authenticate(request);
            if (user.isEmpty()) {
                reject(response, HttpStatus.UNAUTHORIZED, "Please sign in again");
                return;
            }
            UserEntity u = user.get();
            CurrentUser.set(new CurrentUser(u.getId(), u.getEmail(), u.getAdminName(), u.getRole()));

            String path = request.getRequestURI();
            if (path.startsWith("/api/v1/admin/") && !UserRole.ADMIN.equals(u.getRole())) {
                reject(response, HttpStatus.FORBIDDEN, "Only admins can do this");
                return;
            }

            if (BRANDLESS_PREFIXES.stream().noneMatch(path::startsWith)) {
                String header = request.getHeader(BRAND_HEADER);
                Optional<BrandEntity> brand = parseId(header).flatMap(brandRepository::findById);
                if (brand.isEmpty()) {
                    reject(response, HttpStatus.BAD_REQUEST, "Choose a brand first");
                    return;
                }
                if (!Boolean.TRUE.equals(brand.get().getActive())) {
                    reject(response, HttpStatus.FORBIDDEN, "This brand is deactivated");
                    return;
                }
                BrandContext.set(brand.get().getId());
            }

            chain.doFilter(request, response);
        } finally {
            BrandContext.clear();
            CurrentUser.clear();
        }
    }

    private Optional<UserEntity> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        Map<String, Object> claims = jwtService.validateAndExtractClaims(header.substring(7).trim());
        if (claims == null || !(claims.get("userId") instanceof Number id)) return Optional.empty();
        return userRepository.findById(id.longValue())
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .filter(u -> UserRole.isValid(u.getRole()));
    }

    private static Optional<Long> parseId(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }
}
