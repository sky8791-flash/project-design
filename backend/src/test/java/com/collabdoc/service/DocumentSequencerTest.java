package com.collabdoc.service;

import com.collabdoc.entity.Document;
import com.collabdoc.entity.OperationLog;
import com.collabdoc.exception.ConflictException;
import com.collabdoc.repository.DocumentRepository;
import com.collabdoc.repository.OperationLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sequencing contract the whole collaboration design rests on: concurrent writers either get a
 * strictly increasing version or are refused, and the operation log never holds two rows for one version.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentSequencerTest {

    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private OperationLogRepository operationLogRepository;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper objectMapper;

    private Long ownerId;
    private String ownerName;
    private Long docId;
    private JsonNode steps;

    @BeforeEach
    void setUp() throws Exception {
        // A real row: created_by carries no foreign key, so a hardcoded id would insert happily and the
        // history assertions would be reading the "unknown user" fallback instead of a name.
        String name = "sequencer-" + UUID.randomUUID().toString().substring(0, 8);
        ownerName = name;
        ownerId = userService.createUser(name, name + "@test.local", "pw123456").getId();
        Document doc = documentService.createDocument("sequencer", ownerId);
        docId = doc.getId();
        steps = objectMapper.readTree("[{\"stepType\":\"replace\",\"from\":1,\"to\":1,"
                + "\"slice\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],"
                + "\"openStart\":0,\"openEnd\":0}}]");
    }

    private int append(int baseVersion, String clientId) {
        return documentService.appendStepBatch(docId, ownerId, baseVersion, clientId, steps, 5, "session-" + clientId);
    }

    @Test
    void firstBatchTakesVersionOneAndIsTheOnlyLogRow() {
        assertThat(append(0, "a")).isEqualTo(1);

        List<OperationLog> log = operationLogRepository.findByDocumentIdOrderByVersionAsc(docId);
        assertThat(log).extracting(OperationLog::getVersion).containsExactly(1);
        assertThat(log.get(0).getCommandType()).isEqualTo("STEPS");
        // The stored shape of command_params is database-specific (H2 reads a JSON column back quoted),
        // so the round-trip the client depends on is covered by the MySQL-level check, not here.
    }

    @Test
    void staleBaseIsRefusedAndCarriesTheCurrentVersion() {
        append(0, "a");

        try {
            append(0, "b");
            throw new AssertionError("expected a conflict for a stale base version");
        } catch (ConflictException expected) {
            assertThat(expected.getCurrentVersion()).isEqualTo(1);
        }

        assertThat(operationLogRepository.findByDocumentIdOrderByVersionAsc(docId))
            .extracting(OperationLog::getVersion).containsExactly(1);
    }

    @Test
    void concurrentWritersOnTheSameBaseProduceExactlyOneCommittedVersion() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String clientId = "client-" + i;
            tasks.add(() -> {
                start.await();
                try {
                    return append(0, clientId);
                } catch (ConflictException expected) {
                    return -1;
                }
            });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        start.countDown();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        pool.shutdown();

        List<Integer> committed = new ArrayList<>();
        for (Future<Integer> future : futures) {
            int outcome = future.get();
            if (outcome > 0) {
                committed.add(outcome);
            }
        }

        assertThat(committed).containsExactly(1);
        assertThat(documentRepository.findById(docId).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void sequentialBasesKeepTheLogContiguousAndUnique() {
        int first = append(0, "a");
        int second = append(first, "b");
        int third = append(second, "c");

        assertThat(List.of(first, second, third)).containsExactly(1, 2, 3);
        assertThat(operationLogRepository.findByDocumentIdOrderByVersionAsc(docId))
            .extracting(OperationLog::getVersion).containsExactly(1, 2, 3);
        // The history view resolves the author through the user row and folds the retained snapshots in as
        // their own entries, so a fabricated owner id would read back as "未知用户" instead of this name.
        assertThat(documentService.getOperationHistory(docId))
            .extracting(com.collabdoc.dto.OperationLogDTO::getUsername)
            .containsExactly("系统检查点", ownerName, ownerName, ownerName);
    }

    @Test
    void checkpointFoldsTheLogButKeepsAReplayablePathToTheHead() {
        int first = append(0, "a");
        int second = append(first, "b");

        documentService.recordCheckpoint(docId, ownerId, second,
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}", Document.FORMAT_DOC_JSON);

        assertThat(operationLogRepository.findByDocumentIdOrderByVersionAsc(docId)).isEmpty();

        var state = documentService.getDocumentState(docId);
        assertThat(state.getVersion()).isEqualTo(second);
        assertThat(state.getCheckpointVersion()).isEqualTo(second);
        assertThat(state.getContentFormat()).isEqualTo(Document.FORMAT_DOC_JSON);
    }

    @Test
    void restoringAnOldVersionWritesItForwardInsteadOfRewinding() {
        append(0, "a");
        int second = append(1, "b");
        // The first checkpoint recorded for a version wins, so this one must target an uncheckpointed version.
        documentService.recordCheckpoint(docId, ownerId, second, "<p>initial</p>", Document.FORMAT_HTML);
        int head = append(second, "c");

        var restored = documentService.restoreVersion(docId, ownerId, second);

        // A restore writes old content forward: the new version is the head plus one, never the old one.
        assertThat(restored.getVersion()).isEqualTo(head + 1);
        assertThat(restored.getContent()).isEqualTo("<p>initial</p>");
        assertThat(documentRepository.findById(docId).orElseThrow().getVersion()).isEqualTo(restored.getVersion());
        assertThat(operationLogRepository.findByDocumentIdOrderByVersionAsc(docId))
            .extracting(OperationLog::getCommandType).containsExactly("RESTORE");
    }

    @Test
    void wholeDocumentWriteRefusesAStaleBaseInsteadOfClobbering() {
        append(0, "a");

        try {
            documentService.putContent(docId, ownerId, "<p>clobber</p>", 0, Document.FORMAT_HTML);
            throw new AssertionError("expected a conflict for a stale base version");
        } catch (ConflictException expected) {
            assertThat(expected.getCurrentVersion()).isEqualTo(1);
        }

        assertThat(documentService.getDocumentState(docId).getContent()).isEmpty();
    }
}
