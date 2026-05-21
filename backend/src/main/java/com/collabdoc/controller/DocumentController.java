package com.collabdoc.controller;

import com.collabdoc.dto.DocumentState;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.DocumentShare;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<Document> createDocument(@RequestBody Map<String, String> request) {
        String title = request.getOrDefault("title", "Untitled");
        Long userId = Long.parseLong(request.getOrDefault("userId", "1"));
        Document doc = documentService.createDocument(title, userId);
        return ResponseEntity.ok(doc);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id, @RequestParam Long userId) {
        if (!documentService.hasAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this document"));
        }
        DocumentState state = documentService.getDocumentState(id);
        return ResponseEntity.ok(state);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDocument(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(request.getOrDefault("userId", "0").toString());
        if (!documentService.hasWriteAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have write access to this document"));
        }
        String content = (String) request.get("content");
        Integer version = request.get("version") instanceof Number
            ? ((Number) request.get("version")).intValue() : 0;
        DocumentState state = documentService.saveDocument(id, content, version != null ? version : 0, userId);
        return ResponseEntity.ok(state);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id, @RequestParam Long userId) {
        try {
            documentService.deleteDocument(id, userId);
            return ResponseEntity.ok(Map.of("message", "Document deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getUserDocuments(@PathVariable Long userId) {
        return ResponseEntity.ok(documentService.getUserDocuments(userId));
    }

    @GetMapping("/shared/{userId}")
    public ResponseEntity<?> getSharedDocuments(@PathVariable Long userId) {
        return ResponseEntity.ok(documentService.getSharedDocuments(userId));
    }

    @PostMapping("/{id}/undo")
    public ResponseEntity<?> undo(@PathVariable Long id, @RequestParam Long userId) {
        if (!documentService.hasWriteAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have write access to this document"));
        }
        try {
            DocumentState state = documentService.undo(id);
            return ResponseEntity.ok(state);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/redo")
    public ResponseEntity<?> redo(@PathVariable Long id, @RequestParam Long userId) {
        if (!documentService.hasWriteAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have write access to this document"));
        }
        try {
            DocumentState state = documentService.redo(id);
            return ResponseEntity.ok(state);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> getOperationHistory(@PathVariable Long id, @RequestParam Long userId) {
        if (!documentService.hasAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this document"));
        }
        List<OperationLogDTO> history = documentService.getOperationHistory(id);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/{id}/restore/{version}")
    public ResponseEntity<?> restoreVersion(@PathVariable Long id, @PathVariable Integer version, @RequestParam Long userId) {
        if (!documentService.hasWriteAccess(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You don't have write access to this document"));
        }
        try {
            DocumentState state = documentService.restoreVersion(id, version);
            return ResponseEntity.ok(state);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<?> shareDocument(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            Long userId = Long.parseLong(request.get("userId").toString());
            Long sharedByUserId = Long.parseLong(request.get("sharedByUserId").toString());
            DocumentShare.Permission permission = DocumentShare.Permission.valueOf(
                request.getOrDefault("permission", "READ_WRITE").toString()
            );
            DocumentShare share = documentService.shareDocument(id, userId, sharedByUserId, permission);
            return ResponseEntity.ok(share);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/share/{userId}")
    public ResponseEntity<?> removeShare(@PathVariable Long id, @PathVariable Long userId, @RequestParam Long requestUserId) {
        try {
            documentService.removeShare(id, userId, requestUserId);
            return ResponseEntity.ok(Map.of("message", "Share removed successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/shares")
    public ResponseEntity<?> getDocumentShares(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.getDocumentShares(id));
    }
}
