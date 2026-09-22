package com.collabdoc.controller;

import com.collabdoc.entity.User;
import com.collabdoc.exception.InvalidCredentialsException;
import com.collabdoc.security.JwtService;
import com.collabdoc.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;

    public UserController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        User user = userService.createUser(
            request.get("username"),
            request.get("email"),
            request.get("password")
        );
        return ResponseEntity.ok(session(user));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        User user = userService.authenticate(request.get("username"), request.get("password"))
            .orElseThrow(() -> new InvalidCredentialsException());

        userService.requireEnabled(user);
        return ResponseEntity.ok(session(user));
    }

    @GetMapping("/lookup")
    public ResponseEntity<?> lookupUser(@RequestParam String userCode) {
        return userService.findByUserCode(userCode)
            .<ResponseEntity<?>>map(user -> ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "userCode", user.getUserCode()
            )))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The token, not a client-declared id, is what every later request is authenticated with. */
    private Map<String, Object> session(User user) {
        return Map.of(
            "id", user.getId(),
            "username", user.getUsername(),
            "userCode", user.getUserCode(),
            "role", user.getRole().name(),
            "token", jwtService.issue(user.getId(), user.getUsername(), user.getRole().name()),
            "expiresIn", jwtService.expiresInSeconds()
        );
    }
}
