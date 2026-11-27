package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.security.CurrentUser;
import com.ozo.contentforge.security.UserRole;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.service.security.JwtService;
import com.ozo.contentforge.service.security.PasswordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordService passwordService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String email, String password) {
    }

    /** Team sign-in for the dashboard and the admin app. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.email() == null || request.email().isBlank() || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required."));
        }
        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(request.email().trim());
        if (userOpt.isEmpty() || !passwordService.verifyPassword(request.password(), userOpt.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password."));
        }
        UserEntity user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getActive()) || !UserRole.isValid(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This account is deactivated. Ask an admin."));
        }
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole(), null, user.getAdminName());
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", AdminController.teamView(user));
        return ResponseEntity.ok(response);
    }

    /** The signed-in team member (the request already passed AuthenticationFilter). */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Long id = CurrentUser.get().orElseThrow(() -> new SecurityException("Please sign in again")).id();
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new SecurityException("Please sign in again"));
        return Map.of("user", AdminController.teamView(user));
    }
}
