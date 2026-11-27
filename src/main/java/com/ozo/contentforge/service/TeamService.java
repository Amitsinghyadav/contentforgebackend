package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.security.CurrentUser;
import com.ozo.contentforge.security.UserRole;
import com.ozo.contentforge.service.security.PasswordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/** Team accounts: admins and team members. Clients have no logins during the pilot. */
@Service
public class TeamService {

    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserRepository userRepository;
    private final PasswordService passwordService;

    public TeamService(UserRepository userRepository, PasswordService passwordService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
    }

    public List<UserEntity> list() {
        return userRepository.findAll().stream()
                .sorted((a, b) -> a.getEmail().compareToIgnoreCase(b.getEmail()))
                .toList();
    }

    @Transactional
    public UserEntity create(String email, String name, String role, String password) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (!UserRole.isValid(role)) throw new IllegalArgumentException("Role must be ADMIN or TEAM_MEMBER");
        checkPassword(password);
        String clean = email.trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(clean).isPresent()) {
            throw new IllegalStateException("A team member with this email already exists");
        }
        return userRepository.save(UserEntity.builder()
                .email(clean)
                .adminName(name != null ? name.trim() : null)
                .role(role)
                .passwordHash(passwordService.hashPassword(password))
                .active(true)
                .build());
    }

    @Transactional
    public UserEntity update(Long id, String name, String role, Boolean active, String newPassword) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Team member not found"));
        boolean isSelf = CurrentUser.get().map(c -> c.id().equals(id)).orElse(false);
        if (name != null) user.setAdminName(name.trim());
        if (role != null) {
            if (!UserRole.isValid(role)) throw new IllegalArgumentException("Role must be ADMIN or TEAM_MEMBER");
            if (isSelf && !UserRole.ADMIN.equals(role)) throw new IllegalStateException("You can't remove your own admin role");
            user.setRole(role);
        }
        if (active != null) {
            if (isSelf && !active) throw new IllegalStateException("You can't deactivate your own account");
            user.setActive(active);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            checkPassword(newPassword);
            user.setPasswordHash(passwordService.hashPassword(newPassword));
        }
        return userRepository.save(user);
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
