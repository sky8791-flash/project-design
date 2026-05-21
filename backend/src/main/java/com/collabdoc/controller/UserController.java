package com.collabdoc.controller;

import com.collabdoc.entity.User;
import com.collabdoc.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        try {
            User user = userService.createUser(
                request.get("username"),
                request.get("email"),
                request.get("password")
            );
            return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "userCode", user.getUserCode()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        boolean authenticated = userService.authenticate(
            request.get("username"),
            request.get("password")
        );
        if (authenticated) {
            User user = userService.findByUsername(request.get("username")).orElseThrow();
            return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "userCode", user.getUserCode()
            ));
        }
        return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
    }

    @GetMapping("/lookup")
    public ResponseEntity<?> lookupUser(@RequestParam String userCode) {
        return userService.findByUserCode(userCode)
            .map(user -> ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "userCode", user.getUserCode()
            )))
            .orElse(ResponseEntity.status(404).body(Map.of("error", "User not found")));
    }
}
