package com.collabdoc.service;

import com.collabdoc.entity.DocumentShare;
import com.collabdoc.entity.User;
import com.collabdoc.repository.DocumentShareRepository;
import com.collabdoc.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AdminService {

    private final UserRepository userRepository;
    private final DocumentShareRepository documentShareRepository;

    public AdminService(UserRepository userRepository, DocumentShareRepository documentShareRepository) {
        this.userRepository = userRepository;
        this.documentShareRepository = documentShareRepository;
    }

    public void verifyAdmin(Long operatorId) {
        User operator = userRepository.findById(operatorId)
            .orElseThrow(() -> new RuntimeException("Operator not found"));
        if (operator.getRole() != User.Role.ADMIN) {
            throw new RuntimeException("Access denied: admin role required");
        }
    }

    public Page<User> listUsers(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByUsernameContainingOrEmailContaining(keyword, keyword, pageable);
    }

    public User getUser(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User updateRole(Long id, User.Role role) {
        User user = getUser(id);
        user.setRole(role);
        return userRepository.save(user);
    }

    public User updateEnabled(Long id, boolean enabled) {
        User user = getUser(id);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    public String resetPassword(Long id) {
        User user = getUser(id);
        String rawPassword = UUID.randomUUID().toString().substring(0, 8);
        user.setPasswordHash(hashPassword(rawPassword));
        userRepository.save(user);
        return rawPassword;
    }

    public void deleteUser(Long id) {
        User user = getUser(id);
        List<DocumentShare> shares = documentShareRepository.findByUserId(id);
        documentShareRepository.deleteAll(shares);
        userRepository.delete(user);
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
