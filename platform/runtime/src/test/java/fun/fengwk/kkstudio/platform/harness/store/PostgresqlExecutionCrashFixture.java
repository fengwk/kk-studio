package fun.fengwk.kkstudio.platform.harness.store;

import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalStage;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionHandle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRegistration;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.harness.builtin.store.PostgresqlBuiltinHarnessStore;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 真实 PostgreSQL + 真实文件锁的跨 JVM 崩溃恢复夹具，只由 {@link PostgresqlExecutionCrashRecoveryTest} 以独立 JVM 启动。
 *
 * <p>{@code writer} 模式按冻结的 A1 端口登记一次本地 TOOL 执行：PREPARED 落盘 → 数据库登记提交 → attach 当前线程 body，随后写出 ready
 * 标记并阻塞等待父进程 SIGKILL；崩溃前不得 detach body、不得写任何退出证明。{@code recover} 模式用同一日志目录与同一数据库走一次真实首轮恢复，
 * 把恢复结果与权威事实写入结果文件供父进程断言。
 *
 * <p>凭据只从 0600 配置文件读取，绝不进入命令行或日志；所有路径都来自该文件，子进程不做任何默认值兜底。
 */
public final class PostgresqlExecutionCrashFixture {

  private static final String WRITER = "writer";
  private static final String RECOVER = "recover";

  /** 组或其他用户可用的权限一律拒绝：凭据文件只允许属主读写。 */
  private static final Set<PosixFilePermission> SHARED_PERMISSIONS =
      Set.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  private PostgresqlExecutionCrashFixture() {}

  /**
   * @param args {@code <writer|recover> <config> <output>}：config 是 0600 的 fixture 配置，output 是
   *     writer 的 ready 标记或 recover 的结果文件
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("usage: <writer|recover> <config> <output>");
    }
    Properties config = loadConfig(Path.of(args[1]));
    Path output = Path.of(args[2]);
    switch (args[0]) {
      case WRITER -> runWriter(config, output);
      case RECOVER -> runRecover(config, output);
      default -> throw new IllegalArgumentException("unknown fixture mode: " + args[0]);
    }
  }

  private static void runWriter(Properties config, Path readyFile) throws Exception {
    HarnessStore store = store(config);
    LocalExecutionIdentity identity = identity(config);
    try (FileExecutionExitJournal journal =
        new FileExecutionExitJournal(Path.of(required(config, "journalDirectory")))) {
      ExecutionLifecycle lifecycle =
          new ExecutionLifecycle(
              store, journal, new ExecutionExitConfirmer(store, journal, Clock.systemUTC()));
      requireEmptyRecovery(
          lifecycle.start(), "the crash writer requires an untouched journal directory");
      LocalExecutionHandle handle = lifecycle.begin(identity, registration(store, identity));
      if (!handle.registered()) {
        throw new IllegalStateException(
            "execution registration did not commit: " + identity.executionId());
      }
      ExecutionExitFact registered = handle.fact();
      if (registered.state() != ExecutionExitState.UNCONFIRMED) {
        throw new IllegalStateException(
            "registered execution is not unconfirmed: " + registered.state());
      }
      requirePrepared(journal.loadAll(), identity.executionId());
      if (!handle.attachBody(Thread.currentThread())) {
        throw new IllegalStateException("attached body was refused: " + identity.executionId());
      }
      // 到这里 PREPARED 已落盘、登记已提交、body 已 attach：父进程确认这些事实后才 SIGKILL。
      writeAtomically(
          readyFile, "READY executionId=" + identity.executionId() + System.lineSeparator());
      TimeUnit.MILLISECONDS.sleep(Long.parseLong(required(config, "holdMillis")));
      throw new IllegalStateException("the crash writer was not killed within its bound");
    }
  }

  private static void runRecover(Properties config, Path resultFile) throws Exception {
    HarnessStore store = store(config);
    UUID executionId = UUID.fromString(required(config, "executionId"));
    UUID remoteExecutionId = UUID.fromString(required(config, "remoteExecutionId"));
    Properties result = new Properties();
    try (FileExecutionExitJournal journal =
        new FileExecutionExitJournal(Path.of(required(config, "journalDirectory")))) {
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.systemUTC());
      ExecutionLifecycle lifecycle = new ExecutionLifecycle(store, journal, confirmer);
      LocalExecutionRecovery recovery = lifecycle.start();
      // 同一进程内的重复重放必须幂等：已落账的退出证据不得被改写，也不能让 journal 复发。
      confirmer.replayPending();
      ExecutionExitFact local = find(store, executionId);
      ExecutionExitFact remote = find(store, remoteExecutionId);
      ExecutionExitJournalSnapshot snapshot = journal.loadAll();
      result.setProperty("ownerExited", joinUuids(recovery.ownerExited()));
      result.setProperty("cleaned", joinUuids(recovery.cleaned()));
      result.setProperty("retained", joinUuids(recovery.retained()));
      result.setProperty("journalRecords", Integer.toString(snapshot.records().size()));
      result.setProperty("journalFailures", Integer.toString(snapshot.failures().size()));
      result.setProperty("localState", local.state().name());
      result.setProperty("localConfirmedAt", instant(local.confirmedAt()));
      result.setProperty("remoteState", remote.state().name());
      result.setProperty("remoteConfirmedAt", instant(remote.confirmedAt()));
      lifecycle.close();
    }
    // journal 关闭后才落结果文件：父进程看到结果即表示恢复进程已释放目录锁。
    writePropertiesAtomically(resultFile, result);
  }

  private static LocalExecutionRegistration registration(
      HarnessStore store, LocalExecutionIdentity identity) {
    return () ->
        store.transaction(
            tx -> {
              if (tx.threads().lock(identity.threadId()).isEmpty()) {
                throw new IllegalStateException("thread is missing: " + identity.threadId());
              }
              return ExecutionExitLedger.register(
                  tx,
                  identity.executionId(),
                  identity.threadId(),
                  identity.invocationId(),
                  identity.attempt(),
                  identity.ownerLeaseToken(),
                  identity.kind(),
                  null);
            });
  }

  private static ExecutionExitFact find(HarnessStore store, UUID executionId) {
    return store
        .transaction(tx -> tx.exits().find(executionId))
        .orElseThrow(() -> new IllegalStateException("execution fact is missing: " + executionId));
  }

  private static void requireEmptyRecovery(LocalExecutionRecovery recovery, String expectation) {
    if (!recovery.ownerExited().isEmpty()
        || !recovery.cleaned().isEmpty()
        || !recovery.retained().isEmpty()) {
      throw new IllegalStateException(expectation + ": " + recovery);
    }
  }

  /** writer 崩溃后磁盘上必须只有它自己的 PREPARED：REMOTE 只有数据库事实，任何 REMOTE journal 记录都是错误。 */
  private static void requirePrepared(ExecutionExitJournalSnapshot snapshot, UUID executionId) {
    if (!snapshot.failures().isEmpty()) {
      throw new IllegalStateException(
          "journal has unreadable entries: " + snapshot.failures().size());
    }
    if (snapshot.records().size() != 1) {
      throw new IllegalStateException(
          "journal must hold exactly the writer PREPARED: " + snapshot.records().size());
    }
    ExecutionExitJournalRecord record = snapshot.records().getFirst();
    if (!record.executionId().equals(executionId)
        || record.stage() != ExecutionExitJournalStage.PREPARED
        || record.targetState() != null) {
      throw new IllegalStateException(
          "journal entry is not the writer PREPARED: "
              + record.executionId()
              + "/"
              + record.stage());
    }
  }

  private static LocalExecutionIdentity identity(Properties config) {
    return new LocalExecutionIdentity(
        UUID.fromString(required(config, "executionId")),
        UUID.fromString(required(config, "threadId")),
        UUID.fromString(required(config, "invocationId")),
        Integer.parseInt(required(config, "attempt")),
        UUID.fromString(required(config, "ownerLeaseToken")),
        ExecutionExitKind.valueOf(required(config, "kind")));
  }

  private static HarnessStore store(Properties config) {
    DriverManager.setLoginTimeout(15);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(Driver.class.getName());
    dataSource.setUrl(required(config, "jdbcUrl"));
    dataSource.setUsername(required(config, "username"));
    dataSource.setPassword(required(config, "password"));
    return new PostgresqlBuiltinHarnessStore(
        dataSource, new DataSourceTransactionManager(dataSource), UUID::randomUUID);
  }

  private static Properties loadConfig(Path path) throws IOException {
    requirePrivateFile(path);
    Properties config = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      config.load(reader);
    }
    return config;
  }

  private static void requirePrivateFile(Path path) throws IOException {
    PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (view == null) {
      return;
    }
    Set<PosixFilePermission> permissions = view.readAttributes().permissions();
    for (PosixFilePermission shared : SHARED_PERMISSIONS) {
      if (permissions.contains(shared)) {
        throw new IllegalStateException("fixture config must not be accessible to other users");
      }
    }
  }

  private static String required(Properties config, String key) {
    String value = config.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing fixture config entry: " + key);
    }
    return value;
  }

  private static String joinUuids(List<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
  }

  private static String instant(Instant value) {
    return value == null ? "" : value.toString();
  }

  private static void writePropertiesAtomically(Path file, Properties properties)
      throws IOException {
    StringBuilder content = new StringBuilder();
    for (String name : properties.stringPropertyNames().stream().sorted().toList()) {
      content.append(name).append('=').append(properties.getProperty(name)).append('\n');
    }
    writeAtomically(file, content.toString());
  }

  private static void writeAtomically(Path file, String content) throws IOException {
    Path temporary =
        file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
    Files.writeString(
        temporary,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    Files.move(
        temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
