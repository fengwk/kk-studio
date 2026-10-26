package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;

class FileExecutionExitJournalTest {

  private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

  @Test
  void writesCanonicalRecordReusesSameProofAndRejectsConflicts(@TempDir Path directory) {
    UUID executionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitJournalRecord first =
          record(executionId, threadId, invocationId, ExecutionExitState.STUDIO_EXITED, T0);
      ExecutionExitJournalRecord canonical = journal.write(first);
      assertEquals(first, canonical);

      ExecutionExitJournalRecord repeated =
          record(
              executionId,
              threadId,
              invocationId,
              ExecutionExitState.STUDIO_EXITED,
              T0.plusMillis(500));
      assertEquals(first, journal.write(repeated));
      assertEquals(1, journal.loadAll().size());

      ExecutionExitJournalRecord replacedIdentity =
          record(
              executionId,
              threadId,
              UUID.randomUUID(),
              ExecutionExitState.STUDIO_EXITED,
              T0.plusSeconds(1));
      assertThrows(ExecutionExitJournalException.class, () -> journal.write(replacedIdentity));
      ExecutionExitJournalRecord replacedTarget =
          record(executionId, threadId, invocationId, ExecutionExitState.NOT_STARTED, T0);
      assertThrows(ExecutionExitJournalException.class, () -> journal.write(replacedTarget));
      assertEquals(first, journal.loadAll().getFirst());
    }
  }

  @Test
  void enforcesExclusiveDirectoryLockAndPersistsAcrossReopen(@TempDir Path directory) {
    UUID executionId = UUID.randomUUID();
    ExecutionExitJournalRecord record =
        record(
            executionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(record);
      assertThrows(
          ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(directory));
    }
    try (FileExecutionExitJournal reopened = new FileExecutionExitJournal(directory)) {
      assertEquals(record, reopened.loadAll().getFirst());
      reopened.delete(executionId);
      assertTrue(reopened.loadAll().isEmpty());
    }
  }

  @Test
  void usesOwnerOnlyPermissions(@TempDir Path directory) throws IOException {
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(
          record(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              ExecutionExitState.STUDIO_EXITED,
              T0));
      if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
        assertEquals(
            PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        try (var files = Files.list(directory)) {
          for (Path file :
              files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
          }
        }
      }
    }
  }

  @Test
  void corruptRecordFailsClosedWithoutDeletingFile(@TempDir Path directory) throws IOException {
    Path corrupt = directory.resolve(UUID.randomUUID() + ".json");
    Files.writeString(corrupt, "{\"executionId\":\"not-a-uuid\"}");
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    }
    assertTrue(Files.exists(corrupt));
    assertFalse(Files.readString(corrupt).isBlank());
  }

  @Test
  void symbolicLinkEntryFailsClosed(@TempDir Path directory) throws IOException {
    Path target = directory.resolve("outside.tmp");
    Files.writeString(target, "{}");
    Path entry = directory.resolve(UUID.randomUUID() + ".json");
    Files.createSymbolicLink(entry, target);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    }
    assertTrue(Files.isSymbolicLink(entry));
  }

  @Test
  void recordRejectsEvidenceThatDoesNotMatchItsKind() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ExecutionExitState.REMOTE_ACKNOWLEDGED,
                T0));
  }

  private static ExecutionExitJournalRecord record(
      UUID executionId,
      UUID threadId,
      UUID invocationId,
      ExecutionExitState state,
      Instant confirmedAt) {
    return new ExecutionExitJournalRecord(
        executionId,
        threadId,
        invocationId,
        1,
        "lease-1",
        ExecutionExitKind.TOOL,
        null,
        state,
        confirmedAt);
  }
}
