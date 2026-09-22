package com.collabdoc.service;

import com.collabdoc.entity.User;
import com.collabdoc.exception.ForbiddenException;
import com.collabdoc.exception.NotFoundException;
import com.collabdoc.repository.DocumentRepository;
import com.collabdoc.repository.DocumentShareRepository;
import com.collabdoc.repository.NotificationRepository;
import com.collabdoc.repository.OperationLogRepository;
import com.collabdoc.repository.UserRepository;
import com.collabdoc.security.UserSecurity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class AdminService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentShareRepository documentShareRepository;
    private final NotificationRepository notificationRepository;
    private final OperationLogRepository operationLogRepository;
    private final DocumentService documentService;
    private final UserService userService;

    public AdminService(UserRepository userRepository,
                        DocumentRepository documentRepository,
                        DocumentShareRepository documentShareRepository,
                        NotificationRepository notificationRepository,
                        OperationLogRepository operationLogRepository,
                        DocumentService documentService,
                        UserService userService) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.documentShareRepository = documentShareRepository;
        this.notificationRepository = notificationRepository;
        this.operationLogRepository = operationLogRepository;
        this.documentService = documentService;
        this.userService = userService;
    }

    public Page<User> listUsers(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByUsernameContainingOrEmailContaining(keyword, keyword, pageable);
    }

    public User getUser(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    public User updateRole(Long id, User.Role role) {
        User user = getUser(id);
        // An admin who demotes themselves loses the right to undo it, so refuse the self-downgrade.
        if (id.equals(UserSecurity.currentUserId()) && role != User.Role.ADMIN) {
            throw new ForbiddenException("You cannot remove your own admin role");
        }
        user.setRole(role);
        return userRepository.save(user);
    }

    public User updateEnabled(Long id, boolean enabled) {
        User user = getUser(id);
        if (!enabled && id.equals(UserSecurity.currentUserId())) {
            throw new ForbiddenException("You cannot disable your own account");
        }
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    /** The generated password is shown once; only its BCrypt digest is stored. */
    public String resetPassword(Long id) {
        String rawPassword = UUID.randomUUID().toString().substring(0, 8);
        userService.setPassword(id, rawPassword);
        return rawPassword;
    }

    /** Deleting an account takes its documents with it; the FKs leave no other choice. */
    public void deleteUser(Long id) {
        User user = getUser(id);
        if (id.equals(UserSecurity.currentUserId())) {
            throw new ForbiddenException("You cannot delete your own account");
        }

        documentRepository.findByCreatedBy(id)
            .forEach(doc -> documentService.purgeDocument(doc.getId()));
        documentShareRepository.deleteByUserId(id);
        notificationRepository.deleteByUserId(id);
        operationLogRepository.deleteByUserId(id);
        userRepository.delete(user);
    }
}
