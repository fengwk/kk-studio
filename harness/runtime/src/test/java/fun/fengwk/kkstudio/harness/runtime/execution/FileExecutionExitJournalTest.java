package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

  @Test
  void failedLockProbeIsClosedBeforeTheDirectoryCanBeReused(@TempDir Path directory)
      throws IOException {
    Path lockPath = directory.resolve(".lock");
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock()) {
      assertThrows(
          ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(directory));
    }
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(
          record(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              ExecutionExitState.STUDIO_EXITED,
              T0));
      assertEquals(1, journal.loadAll().size());
    }
  }

  @Test
  void constructorRejectsInvalidDirectoryShapesAndCreatesNestedDirectory(@TempDir Path parent)
      throws IOException {
    Path regularFile = parent.resolve("regular");
    Files.writeString(regularFile, "x");
    assertThrows(
        ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(regularFile));

    Path link = parent.resolve("link");
    Files.createSymbolicLink(link, parent);
    assertThrows(ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(link));

    Path lockDirectory = parent.resolve("lock-directory");
    Files.createDirectories(lockDirectory);
    Files.createDirectory(lockDirectory.resolve(".lock"));
    assertThrows(
        ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(lockDirectory));

    Path nested = parent.resolve("nested/a/b");
    try (FileExecutionExitJournal ignored = new FileExecutionExitJournal(nested)) {
      assertTrue(Files.isDirectory(nested));
    }

    Path blockedParent = parent.resolve("blocked");
    Files.writeString(blockedParent, "x");
    assertThrows(
        ExecutionExitJournalException.class,
        () -> new FileExecutionExitJournal(blockedParent.resolve("child")));
  }

  @Test
  void writeAndDeleteReportNonRegularTarget(@TempDir Path directory) throws IOException {
    UUID executionId = UUID.randomUUID();
    Path target = directory.resolve(executionId + ".json");
    Files.createDirectories(target);
    Files.writeString(target.resolve("child"), "x");

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      assertThrows(
          ExecutionExitJournalException.class,
          () ->
              journal.write(
                  record(
                      executionId,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      ExecutionExitState.STUDIO_EXITED,
                      T0)));
      assertThrows(ExecutionExitJournalException.class, () -> journal.delete(executionId));
      assertTrue(Files.isDirectory(target));
    }
  }

  @Test
  void loadAllRejectsRecordStoredUnderAnotherExecutionId(@TempDir Path directory)
      throws IOException {
    UUID executionId = UUID.randomUUID();
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(
          record(
              executionId,
              UUID.randomUUID(),
              UUID.randomUUID(),
              ExecutionExitState.STUDIO_EXITED,
              T0));
    }
    Path stored = directory.resolve(executionId + ".json");
    Path renamed = directory.resolve(UUID.randomUUID() + ".json");
    Files.move(stored, renamed);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    }
    assertTrue(Files.exists(renamed));
  }

  @Test
  void operationsAfterCloseFailAndCloseIsIdempotent(@TempDir Path directory) {
    FileExecutionExitJournal journal = new FileExecutionExitJournal(directory);
    UUID executionId = UUID.randomUUID();
    journal.write(
        record(
            executionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0));
    journal.close();
    journal.close();

    assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    assertThrows(ExecutionExitJournalException.class, () -> journal.delete(executionId));
    assertThrows(
        ExecutionExitJournalException.class,
        () ->
            journal.write(
                record(
                    executionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExecutionExitState.STUDIO_EXITED,
                    T0)));
  }

  @Test
  void remoteRecordRoundTripsParentAndTarget(@TempDir Path directory) {
    ExecutionExitJournalRecord record =
        new ExecutionExitJournalRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "lease-1",
            ExecutionExitKind.REMOTE,
            UUID.randomUUID(),
            ExecutionExitState.REMOTE_ACKNOWLEDGED,
            T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(record);
      assertEquals(record, journal.loadAll().getFirst());
    }
  }

  @Test
  void corruptFieldShapeFailsClosed(@TempDir Path directory) throws IOException {
    Path entry = directory.resolve(UUID.randomUUID() + ".json");
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      Files.writeString(
          entry,
          "{\"executionId\":\""
              + UUID.randomUUID()
              + "\",\"threadId\":\""
              + UUID.randomUUID()
              + "\",\"invocationId\":\""
              + UUID.randomUUID()
              + "\",\"attempt\":1,\"ownerLeaseToken\":\"lease-1\",\"kind\":\"TOOL\","
              + "\"parentExecutionId\":null,\"targetState\":\"STUDIO_EXITED\"}");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);

      Files.writeString(
          entry,
          "{\"executionId\":\""
              + UUID.randomUUID()
              + "\",\"threadId\":\""
              + UUID.randomUUID()
              + "\",\"invocationId\":\""
              + UUID.randomUUID()
              + "\",\"attempt\":1,\"ownerLeaseToken\":\"lease-1\",\"kind\":\"TOOL\","
              + "\"parentExecutionId\":null,\"targetState\":\"STUDIO_EXITED\","
              + "\"confirmedAtX\":\""
              + T0
              + "\"}");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);

      Files.writeString(
          entry,
          "{\"executionId\":\""
              + UUID.randomUUID()
              + "\",\"threadId\":\""
              + UUID.randomUUID()
              + "\",\"invocationId\":\""
              + UUID.randomUUID()
              + "\",\"attempt\":\"one\",\"ownerLeaseToken\":\"lease-1\",\"kind\":\"TOOL\","
              + "\"parentExecutionId\":null,\"targetState\":\"STUDIO_EXITED\","
              + "\"confirmedAt\":\""
              + T0
              + "\"}");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);

      Files.writeString(
          entry,
          "{\"executionId\":\""
              + UUID.randomUUID()
              + "\",\"threadId\":\""
              + UUID.randomUUID()
              + "\",\"invocationId\":\""
              + UUID.randomUUID()
              + "\",\"attempt\":1,\"ownerLeaseToken\":\"\",\"kind\":\"TOOL\","
              + "\"parentExecutionId\":null,\"targetState\":\"STUDIO_EXITED\","
              + "\"confirmedAt\":\""
              + T0
              + "\"}");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    }
  }

  @Test
  void unreadableRecordFailsClosed(@TempDir Path directory) throws IOException {
    Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    Path entry = directory.resolve(UUID.randomUUID() + ".json");
    Files.writeString(entry, "{}");
    Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("---------"));
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
    } finally {
      Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rw-------"));
    }
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
