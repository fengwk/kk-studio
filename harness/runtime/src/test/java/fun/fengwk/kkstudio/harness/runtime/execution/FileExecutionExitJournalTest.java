package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
  void postRenameForceFailureKeepsRecordAndRepeatsForceForExistingProof(@TempDir Path directory)
      throws Exception {
    assumePosixNonRoot(directory);
    UUID executionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    ExecutionExitJournalRecord renamed =
        record(executionId, threadId, invocationId, ExecutionExitState.STUDIO_EXITED, T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory);
        AutoCloseable restore = directoryPermissions(directory, "-wx------")) {
      ExecutionExitJournalException first =
          assertThrows(ExecutionExitJournalException.class, () -> journal.write(renamed));
      assertTrue(first.getMessage().contains("force"));
      assertTrue(
          Files.exists(directory.resolve(executionId + ".json")),
          "atomic rename must complete before the directory force fails");

      // 已存在 canonical record 也必须重新 force；不能因为读取到旧记录就跳过持久性保证。
      ExecutionExitJournalException repeated =
          assertThrows(
              ExecutionExitJournalException.class,
              () ->
                  journal.write(
                      record(
                          executionId,
                          threadId,
                          invocationId,
                          ExecutionExitState.STUDIO_EXITED,
                          T0.plusSeconds(2))));
      assertTrue(repeated.getMessage().contains("force"));

      restore.close();
      ExecutionExitJournalRecord canonical =
          journal.write(
              record(
                  executionId,
                  threadId,
                  invocationId,
                  ExecutionExitState.STUDIO_EXITED,
                  T0.plusSeconds(3)));
      assertEquals(renamed, canonical, "the original canonical evidence and time must be reused");
      assertEquals(List.of(renamed), journal.loadAll());
      assertNoTemporaryFiles(directory);
    }
  }

  @Test
  void directoryWithoutWriteRejectsTemporaryCreationAndLeavesNoJsonOrTemporaryFile(
      @TempDir Path directory) throws Exception {
    assumePosixNonRoot(directory);
    UUID executionId = UUID.randomUUID();
    ExecutionExitJournalRecord record =
        record(
            executionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory);
        AutoCloseable restore = directoryPermissions(directory, "r-x------")) {
      assertThrows(ExecutionExitJournalException.class, () -> journal.write(record));
      Path target = directory.resolve(executionId + ".json");
      assertFalse(Files.exists(target));
      assertNoTemporaryFiles(directory);
      assertNoJsonFiles(directory);

      restore.close();
      assertEquals(record, journal.write(record));
      assertEquals(List.of(record), journal.loadAll());
    }
  }

  @Test
  void unreadableDirectoryFailsClosedAndKeepsExistingRecord(@TempDir Path directory)
      throws Exception {
    assumePosixNonRoot(directory);
    ExecutionExitJournalRecord record =
        record(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(record);
      try (AutoCloseable restore = directoryPermissions(directory, "---------")) {
        assertThrows(ExecutionExitJournalException.class, journal::loadAll);
        restore.close();
        assertEquals(List.of(record), journal.loadAll(), "the existing record must survive");
      }
    }
  }

  @Test
  void constructorFailsWithoutAcquiringChannelWhenLockFileIsNotWritable(@TempDir Path directory)
      throws Exception {
    assumePosixNonRoot(directory);
    Path lockPath = directory.resolve(".lock");
    Files.writeString(lockPath, "");

    try (AutoCloseable restore = filePermissions(lockPath, "r--------")) {
      ExecutionExitJournalException failure =
          assertThrows(
              ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(directory));
      assertTrue(failure.getMessage().contains("cannot lock"));
    }

    ExecutionExitJournalRecord record =
        record(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(record);
      assertEquals(List.of(record), journal.loadAll());
    }
  }

  @Test
  void crossProcessLockHolderPreventsASecondJournalProcess(@TempDir Path directory)
      throws Exception {
    try (CrossProcessLock holder = CrossProcessLock.start(directory)) {
      holder.awaitLocked();
      ExecutionExitJournalException locked =
          assertThrows(
              ExecutionExitJournalException.class, () -> new FileExecutionExitJournal(directory));
      assertTrue(locked.getMessage().contains("already locked"));
    }

    ExecutionExitJournalRecord record =
        record(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(record);
      assertEquals(List.of(record), journal.loadAll());
    }
  }

  @Test
  void strictJsonParsingRejectsNonObjectDuplicateKeysAndTrailingTokens(@TempDir Path directory)
      throws IOException {
    UUID executionId = UUID.randomUUID();
    ExecutionExitJournalRecord valid =
        record(
            executionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExecutionExitState.STUDIO_EXITED,
            T0);
    Path entry = directory.resolve(executionId + ".json");

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(valid);
      String template = Files.readString(entry);

      Files.writeString(entry, "[]");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
      assertTrue(Files.exists(entry));

      Files.writeString(entry, template.replace("\"attempt\":1", "\"attempt\":1,\"attempt\":1"));
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
      assertTrue(Files.exists(entry));

      Files.writeString(entry, template + "{}");
      assertThrows(ExecutionExitJournalException.class, journal::loadAll);
      assertTrue(Files.exists(entry));
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

  private static void assumePosixNonRoot(Path directory) throws IOException {
    Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    Assumptions.assumeFalse(
        "root".equals(System.getProperty("user.name")),
        "root bypasses POSIX directory permission checks");
  }

  /** 设置测试目录权限，并在 TWR 关闭时恢复 0700；显式提前 close 后再次 close 仍幂等。 */
  private static AutoCloseable directoryPermissions(Path directory, String permissions)
      throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(permissions));
    return () ->
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  /** 设置文件权限，并在 TWR 关闭时恢复 0600。 */
  private static AutoCloseable filePermissions(Path file, String permissions) throws IOException {
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
    return () -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
  }

  /** 真实跨进程锁持有者：子进程用同一 java.home 启动，握手完成后阻塞等待父进程关闭 stdin。 */
  private static final class CrossProcessLock implements AutoCloseable {

    private final Process process;
    private final FutureTask<String> locked;
    private final Thread reader;

    private CrossProcessLock(Process process, FutureTask<String> locked, Thread reader) {
      this.process = process;
      this.locked = locked;
      this.reader = reader;
    }

    static CrossProcessLock start(Path directory) throws IOException {
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      Process process =
          new ProcessBuilder(
                  java, "-cp", classpath, LockHolderProbe.class.getName(), directory.toString())
              .redirectErrorStream(true)
              .start();
      FutureTask<String> locked =
          new FutureTask<>(
              new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                  ::readLine);
      Thread reader = new Thread(locked, "execution-exit-journal-lock-probe");
      reader.setDaemon(true);
      reader.start();
      return new CrossProcessLock(process, locked, reader);
    }

    void awaitLocked() throws Exception {
      String line = locked.get(10, TimeUnit.SECONDS);
      if (!"LOCKED".equals(line)) {
        throw new IllegalStateException("journal lock holder did not acquire the lock: " + line);
      }
    }

    @Override
    public void close() throws Exception {
      boolean interrupted = Thread.interrupted();
      Exception failure = null;

      try {
        process.getOutputStream().close();
      } catch (IOException | RuntimeException error) {
        failure = addFailure(failure, error);
      }

      boolean graceful = false;
      try {
        graceful = process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        interrupted = true;
        failure = addFailure(failure, error);
      }
      if (graceful) {
        int exitCode = process.exitValue();
        if (exitCode != 0) {
          failure =
              addFailure(
                  failure,
                  new IllegalStateException("journal lock holder exited with status " + exitCode));
        }
      } else {
        failure =
            addFailure(
                failure,
                new IllegalStateException("journal lock holder required forced termination"));
        try {
          process.destroyForcibly();
        } catch (RuntimeException error) {
          failure = addFailure(failure, error);
        }
        boolean terminated = false;
        try {
          terminated = process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          interrupted = true;
          failure = addFailure(failure, error);
        }
        if (!terminated) {
          failure =
              addFailure(
                  failure,
                  new IllegalStateException(
                      "journal lock holder did not exit after forced termination"));
        }
      }

      try {
        process.getInputStream().close();
      } catch (IOException | RuntimeException error) {
        failure = addFailure(failure, error);
      }

      try {
        reader.join(TimeUnit.SECONDS.toMillis(5));
      } catch (InterruptedException error) {
        interrupted = true;
        failure = addFailure(failure, error);
      }
      if (reader.isAlive()) {
        locked.cancel(true);
        try {
          reader.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException error) {
          interrupted = true;
          failure = addFailure(failure, error);
        }
        if (reader.isAlive()) {
          failure =
              addFailure(
                  failure, new IllegalStateException("journal lock holder reader did not exit"));
        }
      }

      try {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
      } catch (RuntimeException error) {
        failure = addFailure(failure, error);
      }

      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (failure != null) {
        throw failure;
      }
    }

    private static Exception addFailure(Exception primary, Exception next) {
      if (primary == null) {
        return next;
      }
      primary.addSuppressed(next);
      return primary;
    }
  }

  /** test-only 子进程入口：持有真实 journal 目录锁，直到父进程关闭 stdin。 */
  public static final class LockHolderProbe {

    private LockHolderProbe() {}

    public static void main(String[] args) throws Exception {
      try (FileExecutionExitJournal ignored = new FileExecutionExitJournal(Path.of(args[0]))) {
        System.out.println("LOCKED");
        System.out.flush();
        while (System.in.read() != -1) {
          // 父进程通过关闭 stdin 请求释放锁。
        }
      }
    }
  }

  private static void assertNoTemporaryFiles(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      assertTrue(
          files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
          "no temporary file may be left behind");
    }
  }

  private static void assertNoJsonFiles(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      assertTrue(
          files.noneMatch(path -> path.getFileName().toString().endsWith(".json")),
          "no journal record may be created");
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
