package com.collabdoc.controller;

import com.collabdoc.dto.DocumentShareView;
import com.collabdoc.dto.DocumentState;
import com.collabdoc.dto.DocumentSummary;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.DocumentShare;
import com.collabdoc.security.AuthUser;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The caller always comes from the authenticated principal; request bodies still carry a {@code userId}
 * where it names the *target* of an action (who a document is shared with), never the caller.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<Document> createDocument(@RequestBody Map<String, String> request,
                                                   @AuthenticationPrincipal AuthUser caller) {
        String title = request.getOrDefault("title", "Untitled");
        return ResponseEntity.ok(documentService.createDocument(title, caller.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentState> getDocument(@PathVariable Long id,
                                                     @AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getDocumentStateFor(id, caller.id()));
    }

    @GetMapping("/search")
    public ResponseEntity<List<DocumentSummary>> search(@AuthenticationPrincipal AuthUser caller,
                                                        @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(documentService.searchDocuments(caller.id(), keyword));
    }

    @PutMapping("/{id}/title")
    public ResponseEntity<Document> rename(@PathVariable Long id,
                                           @RequestBody Map<String, Object> request,
                                           @AuthenticationPrincipal AuthUser caller) {
        String title = request.get("title") == null ? null : String.valueOf(request.get("title"));
        return ResponseEntity.ok(documentService.rename(id, caller.id(), title));
    }

    /**
     * Whole-document write. A stale {@code version} is rejected with 409 and the current sequence, so an
     * overwriting client learns it must rebase instead of silently winning.
     */
    @PutMapping("/{id}")
    public ResponseEntity<DocumentState> updateDocument(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> request,
                                                        @AuthenticationPrincipal AuthUser caller) {
        String content = request.get("content") == null ? "" : String.valueOf(request.get("content"));
        int baseVersion = request.get("version") instanceof Number n ? n.intValue() : 0;
        String format = request.get("contentFormat") == null ? null : String.valueOf(request.get("contentFormat"));
        return ResponseEntity.ok(documentService.putContent(id, caller.id(), content, baseVersion, format));
    }

    /** A client reports the full document state it holds at {@code atSeq}, folding older operations. */
    @PostMapping("/{id}/checkpoint")
    public ResponseEntity<Map<String, Object>> checkpoint(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request,
                                                          @AuthenticationPrincipal AuthUser caller) {
        int atSeq = request.get("atSeq") instanceof Number n ? n.intValue() : 0;
        String content = request.get("content") == null ? "" : String.valueOf(request.get("content"));
        String format = Document.FORMAT_DOC_JSON.equals(request.get("contentFormat"))
                ? Document.FORMAT_DOC_JSON : Document.FORMAT_HTML;
        documentService.recordCheckpoint(id, caller.id(), atSeq, content, format);
        return ResponseEntity.ok(Map.of("recorded", atSeq));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id,
                                                              @AuthenticationPrincipal AuthUser caller) {
        documentService.deleteDocument(id, caller.id());
        return ResponseEntity.ok(Map.of("message", "Document deleted successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<List<DocumentSummary>> myDocuments(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getUserDocuments(caller.id()));
    }

    @GetMapping("/shared")
    public ResponseEntity<List<DocumentSummary>> sharedWithMe(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getSharedDocuments(caller.id()));
    }

    /** Operations the client has not seen yet, oldest first, for closing a sequence gap. */
    @GetMapping("/{id}/operations")
    public ResponseEntity<List<OperationLogDTO>> getOperations(@PathVariable Long id,
                                                               @RequestParam(defaultValue = "0") int after,
                                                               @AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getOperationsAfter(id, caller.id(), after));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<OperationLogDTO>> getOperationHistory(@PathVariable Long id,
                                                                     @AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getOperationHistoryFor(id, caller.id()));
    }

    @PostMapping("/{id}/restore/{version}")
    public ResponseEntity<DocumentState> restoreVersion(@PathVariable Long id, @PathVariable Integer version,
                                                         @AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.restoreVersion(id, caller.id(), version));
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<DocumentShare> shareDocument(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> request,
                                                       @AuthenticationPrincipal AuthUser caller) {
        Long targetUserId = requireId(request.get("userId"), "userId");
        DocumentShare.Permission permission = parsePermission(
                request.get("permission") == null ? null : String.valueOf(request.get("permission")));
        return ResponseEntity.ok(documentService.shareDocument(id, targetUserId, caller.id(), permission));
    }

    @DeleteMapping("/{id}/share/{userId}")
    public ResponseEntity<Map<String, String>> removeShare(@PathVariable Long id, @PathVariable Long userId,
                                                            @AuthenticationPrincipal AuthUser caller) {
        documentService.removeShare(id, userId, caller.id());
        return ResponseEntity.ok(Map.of("message", "Share removed successfully"));
    }

    @GetMapping("/{id}/shares")
    public ResponseEntity<List<DocumentShareView>> getDocumentShares(@PathVariable Long id,
                                                                      @AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(documentService.getDocumentShareViews(id, caller.id()));
    }

    private Long requireId(Object raw, String field) {
        if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private DocumentShare.Permission parsePermission(String raw) {
        if (raw == null) return DocumentShare.Permission.READ_WRITE;
        try {
            return DocumentShare.Permission.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown permission: " + raw);
        }
    }
}
