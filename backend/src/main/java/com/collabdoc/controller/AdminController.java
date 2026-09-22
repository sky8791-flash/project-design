package com.collabdoc.controller;

import com.collabdoc.entity.User;
import com.collabdoc.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Gated by role at the boundary: the ADMIN claim on the token is checked before a handler runs, so the
 * service no longer repeats "verifyAdmin(operatorId)" in every method with an operator id taken from a
 * header.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<User> users = adminService.listUsers(keyword,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                    Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of(
            "content", users.getContent().stream().map(this::toSummary).toList(),
            "totalElements", users.getTotalElements(),
            "totalPages", users.getTotalPages(),
            "currentPage", users.getNumber()
        ));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(toSummary(adminService.getUser(id)));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable Long id,
                                                          @RequestBody Map<String, String> request) {
        User.Role role;
        try {
            role = User.Role.valueOf(request.get("role"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("role must be ADMIN or USER");
        }
        return ResponseEntity.ok(toSummary(adminService.updateRole(id, role)));
    }

    @PutMapping("/users/{id}/enabled")
    public ResponseEntity<Map<String, Object>> updateEnabled(@PathVariable Long id,
                                                             @RequestBody Map<String, Boolean> request) {
        Boolean enabled = request.get("enabled");
        if (enabled == null) throw new IllegalArgumentException("enabled is required");
        return ResponseEntity.ok(toSummary(adminService.updateEnabled(id, enabled)));
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("newPassword", adminService.resetPassword(id)));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ResponseEntity.ok(Map.of("message", "User deleted"));
    }

    private Map<String, Object> toSummary(User user) {
        return Map.of(
            "id", user.getId(),
            "userCode", user.getUserCode(),
            "username", user.getUsername(),
            "email", user.getEmail(),
            "role", user.getRole().name(),
            "enabled", user.isEnabled(),
            "createdAt", user.getCreatedAt()
        );
    }
}
