package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 真实 PostgreSQL 上跨两代 JVM 的崩溃恢复验收：持锁 JVM 在 PREPARED 已落盘、退出事实已提交、body 已 attach 后被 SIGKILL，第二个 JVM
 * 用同一日志目录与同一数据库把它恢复成 {@code OWNER_EXITED}，而同代次的 REMOTE 事实始终未确认。
 *
 * <p>被强杀的 JVM 只经真实文件锁与真实数据库参与判定：杀前第二份 open 必须被拒绝，杀后日志仍在，恢复由新 JVM 的 {@link ExecutionLifecycle}
 * 完成，随后第三个 JVM 重复恢复不得重复确认或改写已落账的证据。PG 容器与测试库由 {@link PostgresqlHarnessStoreFixture} 独占提供，子进程只从 0600
 * 配置文件拿到本容器测试库的连接信息。
 */
class PostgresqlExecutionCrashRecoveryTest {

  private static final String WRITER = "writer";
  private static final String RECOVER = "recover";
  private static final String FIXTURE_MAIN = PostgresqlExecutionCrashFixture.class.getName();

  /** 所有跨进程等待都有界：子进程启动/恢复上限，以及 writer 在父进程杀它之前的自我上限。 */
  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

  private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration KILL_TIMEOUT = Duration.ofSeconds(30);
  private static final long WRITER_HOLD_MILLIS = 60_000L;

  /** SIGKILL 让子进程以 128 + 9 结束；clean shutdown 不可能有这个状态。 */
  private static final int SIGKILL_EXIT_STATUS = 137;

  @TempDir Path tempDirectory;

  @Test
  void sigkilledOwnerIsRecoveredBySecondJvmAndRemoteStaysUnconfirmed() throws Exception {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID executionId = UUID.randomUUID();
    UUID remoteExecutionId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID ownerLeaseToken = UUID.randomUUID();
    UUID environmentId = UUID.randomUUID();
    Path journalDirectory = tempDirectory.resolve("execution-journal");
    Path readyFile = tempDirectory.resolve("writer.ready");
    Path configFile =
        writeConfig(
            executionId,
            remoteExecutionId,
            turn.threadId(),
            invocationId,
            ownerLeaseToken,
            journalDirectory);
    Path writerLog = tempDirectory.resolve("writer.log");

    try (ChildJvm writer = start(WRITER, configFile, readyFile, writerLog)) {
      awaitFile(writer, readyFile, START_TIMEOUT);
      assertTrue(
          Files.readString(readyFile, StandardCharsets.UTF_8).contains(executionId.toString()),
          "the ready marker must belong to this execution");
      assertCrashedPreState(store, executionId, turn.threadId(), journalDirectory);
      // 杀前锁拒绝：持锁 JVM 仍存活时，同一目录的第二份 open 必须失败，而不是分治同一批 journal。
      assertThrows(
          ExecutionExitJournalException.class,
          () -> {
            try (FileExecutionExitJournal ignored =
                new FileExecutionExitJournal(journalDirectory)) {
              throw new IllegalStateException(
                  "a live owner must keep the execution journal directory locked");
            }
          });
      registerRemote(
          store,
          remoteExecutionId,
          executionId,
          turn.threadId(),
          invocationId,
          ownerLeaseToken,
          EnvironmentId.of(environmentId));
      assertEquals(ExecutionExitState.UNCONFIRMED, fact(store, remoteExecutionId).state());

      killWithSigkill(writer);

      // 强杀不是正常关闭：数据库事实仍未确认，PREPARED 仍在磁盘上等待下一进程处理。
      ExecutionExitFact crashed = fact(store, executionId);
      assertEquals(ExecutionExitState.UNCONFIRMED, crashed.state());
      assertNull(crashed.confirmedAt());
      assertTrue(
          Files.isRegularFile(journalDirectory.resolve(executionId + ".json")),
          "a SIGKILL must leave the PREPARED journal entry in place");
    }

    Properties firstRecovery =
        recover(configFile, tempDirectory.resolve("recover-1.result"), "recover-1");
    assertEquals(executionId.toString(), firstRecovery.getProperty("ownerExited"));
    assertEquals("", firstRecovery.getProperty("cleaned"));
    assertEquals("", firstRecovery.getProperty("retained"));
    assertEquals("0", firstRecovery.getProperty("journalRecords"));
    assertEquals("0", firstRecovery.getProperty("journalFailures"));
    assertEquals("OWNER_EXITED", firstRecovery.getProperty("localState"));
    assertEquals("UNCONFIRMED", firstRecovery.getProperty("remoteState"));

    ExecutionExitFact recovered = fact(store, executionId);
    assertEquals(ExecutionExitState.OWNER_EXITED, recovered.state());
    assertNotNull(recovered.confirmedAt());
    assertEquals(recovered.confirmedAt().toString(), firstRecovery.getProperty("localConfirmedAt"));
    ExecutionExitFact remote = fact(store, remoteExecutionId);
    assertEquals(ExecutionExitKind.REMOTE, remote.kind());
    assertEquals(executionId, remote.parentExecutionId());
    assertEquals(EnvironmentId.of(environmentId), remote.environmentId());
    assertEquals(ExecutionExitState.UNCONFIRMED, remote.state());
    assertNull(remote.confirmedAt());
    assertEquals(List.of(remoteExecutionId), unconfirmedExecutions(store, turn.threadId()));

    // 恢复进程必须释放目录锁并删除已确认的记录，下一进程才能接管同一目录。
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(journalDirectory)) {
      assertTrue(journal.loadAll().records().isEmpty());
    }

    // 重复重放安全：第三个 JVM 再次恢复不得重复确认、不得改写已落账的退出证据，REMOTE 仍保持未确认。
    Properties secondRecovery =
        recover(configFile, tempDirectory.resolve("recover-2.result"), "recover-2");
    assertEquals("", secondRecovery.getProperty("ownerExited"));
    assertEquals("", secondRecovery.getProperty("cleaned"));
    assertEquals("", secondRecovery.getProperty("retained"));
    assertEquals("0", secondRecovery.getProperty("journalRecords"));
    assertEquals("OWNER_EXITED", secondRecovery.getProperty("localState"));
    assertEquals(
        firstRecovery.getProperty("localConfirmedAt"),
        secondRecovery.getProperty("localConfirmedAt"));
    assertEquals("UNCONFIRMED", secondRecovery.getProperty("remoteState"));
    assertEquals(recovered.confirmedAt(), fact(store, executionId).confirmedAt());
    assertEquals(List.of(remoteExecutionId), unconfirmedExecutions(store, turn.threadId()));
    assertTrue(jsonFiles(journalDirectory).isEmpty(), "recovery must not leave journal entries");
    assertCredentialBoundary(
        configFile,
        List.of(
            writerLog,
            tempDirectory.resolve("recover-1.log"),
            tempDirectory.resolve("recover-2.log")));
  }

  /** 凭据边界：配置文件 0600、临时目录 0700，子进程命令行只带路径，日志不得回显连接串。 */
  private void assertCredentialBoundary(Path configFile, List<Path> logs) throws IOException {
    assertEquals(
        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(configFile));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tempDirectory));
    String jdbcUrl =
        ((DriverManagerDataSource) PostgresqlHarnessStoreFixture.dataSource()).getUrl();
    for (Path log : logs) {
      assertFalse(
          Files.readString(log, StandardCharsets.UTF_8).contains(jdbcUrl),
          "child logs must not echo the connection url: " + log.getFileName());
    }
  }

  /** writer 崩溃前可观察的状态：PREPARED 已落盘、退出事实已提交且未确认，且只有本地执行写过 journal。 */
  private static void assertCrashedPreState(
      HarnessStore store, UUID executionId, UUID threadId, Path journalDirectory)
      throws IOException {
    ExecutionExitFact registered = fact(store, executionId);
    assertEquals(ExecutionExitState.UNCONFIRMED, registered.state());
    assertEquals(ExecutionExitKind.TOOL, registered.kind());
    assertEquals(threadId, registered.threadId());
    assertNull(registered.confirmedAt());
    List<Path> records = jsonFiles(journalDirectory);
    assertEquals(List.of(journalDirectory.resolve(executionId + ".json")), records);
    String json = Files.readString(records.getFirst(), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"stage\":\"PREPARED\""), "the journal entry must still be PREPARED");
    assertTrue(json.contains("\"targetState\":null"), "PREPARED must not carry an exit target");
  }

  /** REMOTE 只登记数据库事实：同 Thread / invocation / attempt / lease 的精确身份，且绝不写 PREPARED。 */
  private static void registerRemote(
      HarnessStore store,
      UUID remoteExecutionId,
      UUID parentExecutionId,
      UUID threadId,
      UUID invocationId,
      UUID ownerLeaseToken,
      EnvironmentId environmentId) {
    store.transaction(
        tx -> {
          tx.threads()
              .lock(threadId)
              .orElseThrow(() -> new IllegalStateException("thread is missing: " + threadId));
          return ExecutionExitLedger.register(
              tx,
              remoteExecutionId,
              threadId,
              invocationId,
              1,
              ownerLeaseToken,
              ExecutionExitKind.REMOTE,
              parentExecutionId,
              environmentId);
        });
  }

  private static void killWithSigkill(ChildJvm child) throws Exception {
    Process kill =
        new ProcessBuilder("/bin/kill", "-9", Long.toString(child.process().pid()))
            .redirectErrorStream(true)
            .start();
    String killOutput;
    if (!kill.waitFor(KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      kill.destroyForcibly();
      throw new IllegalStateException("cannot deliver SIGKILL to the journal owner");
    }
    try (var stream = kill.getInputStream()) {
      killOutput = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    if (kill.exitValue() != 0) {
      throw new IllegalStateException(
          "cannot deliver SIGKILL to the journal owner: status="
              + kill.exitValue()
              + "; output="
              + killOutput);
    }
    if (!child.process().waitFor(KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException("the sigkilled owner did not terminate:\n" + child.logTail());
    }
    int status = child.process().exitValue();
    if (status != SIGKILL_EXIT_STATUS) {
      throw new IllegalStateException(
          "the owner must die by SIGKILL instead of finishing its shutdown: status="
              + status
              + "\n"
              + child.logTail());
    }
  }

  private Properties recover(Path configFile, Path resultFile, String name) throws Exception {
    try (ChildJvm child =
        start(RECOVER, configFile, resultFile, tempDirectory.resolve(name + ".log"))) {
      awaitExit(child);
    }
    Properties result = new Properties();
    try (Reader reader = Files.newBufferedReader(resultFile, StandardCharsets.UTF_8)) {
      result.load(reader);
    }
    return result;
  }

  private static void awaitExit(ChildJvm child) throws Exception {
    if (!child.process().waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      child.process().destroyForcibly();
      throw new IllegalStateException(
          child.name() + " did not finish within " + CHILD_TIMEOUT + ":\n" + child.logTail());
    }
    int status = child.process().exitValue();
    if (status != 0) {
      throw new IllegalStateException(
          child.name() + " failed with status " + status + ":\n" + child.logTail());
    }
  }

  private static void awaitFile(ChildJvm child, Path file, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(file)) {
        return;
      }
      if (!child.process().isAlive()) {
        throw new IllegalStateException(
            child.name()
                + " exited with status "
                + child.process().exitValue()
                + " before writing "
                + file.getFileName()
                + ":\n"
                + child.logTail());
      }
      Thread.sleep(25L);
    }
    throw new IllegalStateException(
        child.name()
            + " did not write "
            + file.getFileName()
            + " within "
            + timeout
            + ":\n"
            + child.logTail());
  }

  private ChildJvm start(String mode, Path configFile, Path outputFile, Path logFile)
      throws IOException {
    Process process =
        new ProcessBuilder(
                javaBinary(),
                "-cp",
                testClasspath(),
                FIXTURE_MAIN,
                mode,
                configFile.toString(),
                outputFile.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
            .start();
    return new ChildJvm(mode, process, logFile);
  }

  private Path writeConfig(
      UUID executionId,
      UUID remoteExecutionId,
      UUID threadId,
      UUID invocationId,
      UUID ownerLeaseToken,
      Path journalDirectory)
      throws IOException {
    DriverManagerDataSource dataSource =
        (DriverManagerDataSource) PostgresqlHarnessStoreFixture.dataSource();
    Files.setPosixFilePermissions(tempDirectory, PosixFilePermissions.fromString("rwx------"));
    Path configFile = tempDirectory.resolve("fixture.properties");
    Files.createFile(
        configFile,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(
        configFile,
        configuration(
            dataSource,
            executionId,
            remoteExecutionId,
            threadId,
            invocationId,
            ownerLeaseToken,
            journalDirectory),
        StandardCharsets.UTF_8,
        StandardOpenOption.WRITE);
    return configFile;
  }

  /** 凭据只写进 0600 文件：子进程的命令行与日志里只有这个文件路径。 */
  private String configuration(
      DriverManagerDataSource dataSource,
      UUID executionId,
      UUID remoteExecutionId,
      UUID threadId,
      UUID invocationId,
      UUID ownerLeaseToken,
      Path journalDirectory) {
    StringBuilder config = new StringBuilder();
    config.append("jdbcUrl=").append(dataSource.getUrl()).append('\n');
    config.append("username=").append(dataSource.getUsername()).append('\n');
    config.append("password=").append(dataSource.getPassword()).append('\n');
    config.append("journalDirectory=").append(journalDirectory).append('\n');
    config.append("executionId=").append(executionId).append('\n');
    config.append("remoteExecutionId=").append(remoteExecutionId).append('\n');
    config.append("threadId=").append(threadId).append('\n');
    config.append("invocationId=").append(invocationId).append('\n');
    config.append("attempt=1\n");
    config.append("ownerLeaseToken=").append(ownerLeaseToken).append('\n');
    config.append("kind=").append(ExecutionExitKind.TOOL).append('\n');
    config.append("holdMillis=").append(WRITER_HOLD_MILLIS).append('\n');
    return config.toString();
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String testClasspath() {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    if (classpath == null || classpath.isBlank()) {
      throw new IllegalStateException("cannot resolve the test classpath for the crash fixture");
    }
    return classpath;
  }

  private static ExecutionExitFact fact(HarnessStore store, UUID executionId) {
    return store
        .transaction(tx -> tx.exits().find(executionId))
        .orElseThrow(() -> new AssertionError("execution fact is missing: " + executionId));
  }

  private static List<UUID> unconfirmedExecutions(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.exits().loadUnconfirmed(threadId)).stream()
        .map(ExecutionExitFact::executionId)
        .toList();
  }

  private static List<Path> jsonFiles(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted()
          .toList();
    }
  }

  /** 一个有界的子 JVM：输出直落日志文件，close 时确保进程已被杀死并回收。 */
  private static final class ChildJvm implements AutoCloseable {

    private final String name;
    private final Process process;
    private final Path logFile;

    private ChildJvm(String name, Process process, Path logFile) {
      this.name = name;
      this.process = process;
      this.logFile = logFile;
    }

    private String name() {
      return name;
    }

    private Process process() {
      return process;
    }

    @Override
    public void close() throws Exception {
      Exception failure = null;
      try {
        process.getOutputStream().close();
      } catch (IOException error) {
        failure = error;
      }
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      try {
        if (!process.waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          IllegalStateException error =
              new IllegalStateException(
                  name + " did not exit after forced termination:\n" + logTail());
          if (failure == null) {
            failure = error;
          } else {
            failure.addSuppressed(error);
          }
        }
      } catch (InterruptedException error) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        if (failure == null) {
          failure = error;
        } else {
          failure.addSuppressed(error);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }

    private String logTail() {
      try {
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size()));
      } catch (IOException error) {
        return "<no child log: " + error.getClass().getSimpleName() + ">";
      }
    }
  }
}
