package com.collabdoc.controller;

import com.collabdoc.entity.Notification;
import com.collabdoc.security.AuthUser;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Everything here is scoped to the authenticated caller; there is no way to read another inbox. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final DocumentService documentService;

    public NotificationController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public ResponseEntity<List<Notification>> list(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getNotifications(caller.id()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(Map.of("count", documentService.countUnreadNotifications(caller.id())));
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Long>> markRead(@AuthenticationPrincipal AuthUser caller) {
        documentService.markNotificationsRead(caller.id());
        return ResponseEntity.ok(Map.of("unread", documentService.countUnreadNotifications(caller.id())));
    }
}
