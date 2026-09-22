package com.collabdoc.controller;

import com.collabdoc.entity.User;
import com.collabdoc.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(
            @RequestHeader("X-User-Id") Long operatorId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            adminService.verifyAdmin(operatorId);
            Page<User> users = adminService.listUsers(keyword,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
            return ResponseEntity.ok(Map.of(
                "content", users.getContent().stream().map(this::toSummary).toList(),
                "totalElements", users.getTotalElements(),
                "totalPages", users.getTotalPages(),
                "currentPage", users.getNumber()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUser(
            @RequestHeader("X-User-Id") Long operatorId,
            @PathVariable Long id) {
        try {
            adminService.verifyAdmin(operatorId);
            User user = adminService.getUser(id);
            return ResponseEntity.ok(toSummary(user));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateRole(
            @RequestHeader("X-User-Id") Long operatorId,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            adminService.verifyAdmin(operatorId);
            User.Role role = User.Role.valueOf(request.get("role"));
            User user = adminService.updateRole(id, role);
            return ResponseEntity.ok(toSummary(user));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}/enabled")
    public ResponseEntity<?> updateEnabled(
            @RequestHeader("X-User-Id") Long operatorId,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request) {
        try {
            adminService.verifyAdmin(operatorId);
            User user = adminService.updateEnabled(id, request.get("enabled"));
            return ResponseEntity.ok(toSummary(user));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<?> resetPassword(
            @RequestHeader("X-User-Id") Long operatorId,
            @PathVariable Long id) {
        try {
            adminService.verifyAdmin(operatorId);
            String newPassword = adminService.resetPassword(id);
            return ResponseEntity.ok(Map.of("newPassword", newPassword));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(
            @RequestHeader("X-User-Id") Long operatorId,
            @PathVariable Long id) {
        try {
            adminService.verifyAdmin(operatorId);
            adminService.deleteUser(id);
            return ResponseEntity.ok(Map.of("message", "User deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
