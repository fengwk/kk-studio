package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.store.EnvironmentJdbcStore;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.web.WebApplication;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 真实子进程的 {@code WebApplication} 入口：首参 {@code database-upgrade} 必须走离线 CLI 且不启动 Spring
 * Web/Worker，帮助、用法错误、真实空库 prepare、真实连接失败与 DEV/MAIN 的完整 prepare→dry-run→apply→再 apply 都在独立 JVM
 * 里以进程退出码与输出呈现；提交后的目标还必须能被不带 {@code database-upgrade} 首参的正常入口启动，并通过 HTTP 读回转换数据。
 *
 * <p>子进程继承的 JVM 环境会污染 stderr（如 {@code JAVA_TOOL_OPTIONS} 的 "Picked up" 提示），因此启动前从子环境移除这些变量并显式传入非
 * /tmp 的临时目录；输出重定向到私有文件后再读取，避免管道写满造成死锁；超时或中断时都会强杀并回收本次启动的那一个子进程。stderr 断言保持严格：任何额外的诊断行都会失败。
 *
 * <p>正常入口用真实隔离数据面启动：{@link UpgradePostgresTestSupport} 的 PostgreSQL 17 与 {@link
 * UpgradeMinioFixture} 的私有 MinIO，prod profile、仅当前 V1 Flyway、无 worker、回环随机端口，因此读回走的是生产组合根与真实
 * S3/数据库，不用任何测试替身。
 *
 * <p>两种入口模式共用同一批用例：默认用当前测试 classpath 直接运行 {@link WebApplication}（剔除全部 {@code
 * target/test-classes}，避免 同包的测试入口被生产组合根扫描并注入 S3 假件）；设置 {@code
 * -Dkkstudio.upgrade.test.jar=/abs/path.jar} 时先校验该绝对路径上的普通 JAR，再以 {@code java -jar} 运行产物本身。
 *
 * <p>用例只连接可丢弃的隔离容器库与容器 S3，不连接任何实际部署。
 */
class WebApplicationDatabaseUpgradeSubprocessTest {

  private static final long TIMEOUT_SECONDS = 120;

  /** 正常入口从进程启动到就绪 API 报 UP 的有界预算：失败时也必须在有限时间内回收子进程。 */
  private static final long WEB_ENTRY_READY_TIMEOUT_SECONDS = 120;

  /** 强杀后回收子进程的有界等待：只保证本次启动的进程被释放，不枚举任何后代进程。 */
  private static final long CHILD_SHUTDOWN_SECONDS = 30;

  private static final String SENTINEL = "sentinel-subprocess-credential-3312";

  /** 指向待验收的 Fat JAR；未设置时用当前测试 classpath 直接运行入口类。 */
  private static final String TEST_JAR_PROPERTY = "kkstudio.upgrade.test.jar";

  /** 测试类目录：里面的 {@code WebTestApplication} 与生产入口同包，绝不能进入生产 classpath。 */
  private static final Path TEST_CLASSES_DIRECTORY = Path.of("target", "test-classes");

  /** 三类 JVM 启动变量会在 Java 代码执行前向 stderr 打印提示，必须从子环境移除；生产不依赖这些变量。 */
  private static final List<String> JVM_OPTION_ENV_VARIABLES =
      List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** {@code legacy-reference-data-business.sql} 写入的 Skill Package 身份与两个 commit；读回必须原样可见且不含凭据。 */
  private static final String LEGACY_SKILL_PACKAGE = "legacy-skill";

  private static final String LEGACY_SKILL_COMMIT = "1111111111111111111111111111111111111111";

  private static final String LEGACY_SKILL_OBSERVED_HEAD =
      "2222222222222222222222222222222222222222";

  /** 源库的 Flyway 历史表：不在复制清单里，但必须与全部共有表一起证明源库未被改动。 */
  private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

  /** {@code LegacyUpgradeSourceFixture} 写入的源 Environment；提交后必须能被常规读侧读出同一份 install config。 */
  private static final UUID LEGACY_ENVIRONMENT_ID =
      UUID.fromString("0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f");

  /** 整表规范值摘要：行 JSON 文本排序后取 md5，空表用固定标记；因此摘要覆盖全部列值且本身不含任何原始行。 */
  private static final String TABLE_DIGEST =
      "select coalesce(md5(string_agg(to_jsonb(t)::text, E'\\n' order by to_jsonb(t)::text)), '(empty)')"
          + " from %s t";

  @TempDir Path tempDir;

  /** 一次子进程调用的退出码与两条输出流。 */
  private record ProcessResult(int exit, String out, String err) {}

  @Test
  void helpRunsOfflineWithoutSpringAndExitsZero() throws Exception {
    ProcessResult result = runSubprocess("database-upgrade", "--help");
    assertEquals(0, result.exit(), result.err());
    assertTrue(result.out().contains("Usage:"));
    assertTrue(result.out().contains("prepare"));
    assertTrue(result.out().contains("dry-run"));
    assertTrue(result.out().contains("apply"));
    assertFalse(result.out().contains("Starting WebApplication"));
    assertEquals("", result.err());
  }

  @Test
  void unknownCommandExitsWithUsageError() throws Exception {
    ProcessResult result = runSubprocess("database-upgrade", "nope");
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=USAGE"));
    assertEquals(5, result.err().lines().count(), result.err());
  }

  @Test
  void prepareInitializesARealBlankDatabase() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    SimpleDriverDataSource handle = (SimpleDriverDataSource) target;
    Path config =
        UpgradeCliInputFixture.config(
            UpgradeCliInputFixture.ownerOnlyDir(tempDir),
            "prepare.json",
            UpgradeCliInputFixture.prepareConfig(
                handle.getUrl(), handle.getUsername(), handle.getPassword()));

    ProcessResult result =
        runSubprocess("database-upgrade", "prepare", "--config", config.toString());
    assertEquals(0, result.exit(), result.err());
    assertTrue(result.out().contains("mode=prepare"));
    assertTrue(result.out().contains("applied=true"));
    assertEquals("", result.err());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 真实 JDBC 连接失败的 stderr 必须只有 CLI 的固定五行：第三方日志与驱动诊断都不能漏出 URL 或口令。 */
  @Test
  void connectionFailureEmitsOnlyTheFixedErrorBlock() throws Exception {
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    SimpleDriverDataSource handle = (SimpleDriverDataSource) target;
    Path config =
        UpgradeCliInputFixture.config(
            UpgradeCliInputFixture.ownerOnlyDir(tempDir),
            "prepare.json",
            UpgradeCliInputFixture.prepareConfig(handle.getUrl(), handle.getUsername(), SENTINEL));

    ProcessResult result =
        runSubprocess("database-upgrade", "prepare", "--config", config.toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CONNECTION"));
    assertTrue(result.err().contains("error.type=LegacyDatabaseUpgradeException"));
    assertTrue(result.err().contains("outcomeUncertain=false"));
    assertEquals(5, result.err().lines().count(), result.err());
    assertFalse(result.err().contains(SENTINEL));
    assertFalse(result.err().contains("127.0.0.1"));
    assertFalse(result.out().contains(SENTINEL));
    assertEquals("", result.out());
  }

  /** DEV 源在独立 JVM 里走完整链路：prepare 初始化空目标，dry-run 回滚，apply 提交，再 apply 被拒绝，正常生产入口启动并读回，源库不变。 */
  @Test
  void devSourceRunsOfflineCycleAndNormalWebEntryReadback() throws Exception {
    assertUpgradeCycle("dev");
  }

  /** MAIN 源与 DEV 共用同一条真实子进程链路与全部断言。 */
  @Test
  void mainSourceRunsOfflineCycleAndNormalWebEntryReadback() throws Exception {
    assertUpgradeCycle("main");
  }

  /**
   * 一个 profile 的完整真实链路：密集旧源 + 空目标 → CLI prepare/dry-run/apply → 再 apply 被拒绝 → 正常生产入口启动并 HTTP 读回 →
   * 源库不变。
   */
  private void assertUpgradeCycle(String profile) throws Exception {
    SecretKey sourceKey = key(0x2a);
    SecretKey targetKey = key(0x3b);
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(profile);
    LegacyUpgradeSourceFixture.load(source, sourceKey);
    LegacyHarnessHistoryFixture.insert(source);
    LegacyReferenceDataFixture.load(source, profile, sourceKey);
    // 正常入口启动会立刻扫描到期凭据；把遗留行的下一次刷新推到远未来，避免本次验收触发任何外部刷新。
    LegacyUpgradeSourceFixture.deferPluginCredentialRefresh(source);
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    Map<String, String> sourceDigests = sourceDigests(source, profile);

    Path dir = UpgradeCliInputFixture.ownerOnlyDir(tempDir);
    Path sourceKeyFile =
        UpgradeCliInputFixture.key(dir, profile + "-source.key", sourceKey.getEncoded());
    Path targetKeyFile =
        UpgradeCliInputFixture.key(dir, profile + "-target.key", targetKey.getEncoded());
    Path prepareConfig =
        UpgradeCliInputFixture.config(dir, profile + "-prepare.json", prepareJson(target));
    Path convertConfig =
        UpgradeCliInputFixture.config(
            dir,
            profile + "-convert.json",
            convertJson(source, target, sourceKeyFile, targetKeyFile));

    ProcessResult prepared =
        runSubprocess("database-upgrade", "prepare", "--config", prepareConfig.toString());
    assertEquals(0, prepared.exit(), prepared.err());
    assertTrue(prepared.out().contains("mode=prepare"));
    assertTrue(prepared.out().contains("applied=true"));
    assertEquals("", prepared.err());
    UpgradePostgresTestSupport.assertPristine(target);

    ProcessResult dryRun =
        runSubprocess("database-upgrade", "dry-run", "--config", convertConfig.toString());
    assertEquals(0, dryRun.exit(), dryRun.err());
    assertTrue(dryRun.out().contains("mode=dry-run"));
    assertTrue(dryRun.out().contains("profile=" + profile));
    assertTrue(dryRun.out().contains("applied=false"));
    assertEquals("", dryRun.err());
    Map<String, Long> dryRunCounts = rowCounts(dryRun.out());
    assertFalse(dryRunCounts.isEmpty(), dryRun.out());
    // dry-run 必须整体回滚：目标回到 pristine，源库逐表值摘要不变。
    UpgradePostgresTestSupport.assertPristine(target);
    assertEquals(
        sourceDigests, sourceDigests(source, profile), "dry-run must not change the source");

    ProcessResult apply =
        runSubprocess("database-upgrade", "apply", "--config", convertConfig.toString());
    assertEquals(0, apply.exit(), apply.err());
    assertTrue(apply.out().contains("mode=apply"));
    assertTrue(apply.out().contains("profile=" + profile));
    assertTrue(apply.out().contains("applied=true"));
    assertEquals("", apply.err());
    // apply 与 dry-run 必须逐表一致，且提交后的目标能被常规读取。
    assertEquals(dryRunCounts, rowCounts(apply.out()));
    for (Map.Entry<String, Long> row : dryRunCounts.entrySet()) {
      assertEquals(
          row.getValue().longValue(),
          UpgradePostgresTestSupport.count(target, row.getKey()),
          row.getKey());
    }
    assertTargetInstallConfigMatchesSource(source, target);

    // 源库完全不变：仍是同一个冻结 profile（schema/history 身份），且逐表值摘要完全一致。
    assertEquals(profile, sourceProfile(source));
    assertEquals(sourceDigests, sourceDigests(source, profile), "apply must not change the source");

    // 已提交的目标不再是 pristine：同一份配置再 apply 必须拒绝而不是覆盖或重试。
    ProcessResult reapply =
        runSubprocess("database-upgrade", "apply", "--config", convertConfig.toString());
    assertEquals(1, reapply.exit());
    assertTrue(reapply.err().contains("error.stage=TARGET_PRISTINE"), reapply.err());
    assertEquals("", reapply.out());
    assertEquals(
        sourceDigests,
        sourceDigests(source, profile),
        "the refused reapply must not change the source");

    // 提交后的目标必须能被不带 database-upgrade 首参的正常生产入口启动，并读回转换后的数据。
    assertNormalWebEntryReadsConvertedData(profile, target, targetKeyFile);
    assertEquals(
        sourceDigests,
        sourceDigests(source, profile),
        "the normal web entry must not change the source");
  }

  /**
   * 用真实隔离数据面启动正常生产入口（prod profile、当前 V1、无 worker、私有 S3、回环随机端口），等就绪后通过 HTTP 读回转换数据。
   *
   * <p>入口子进程、HTTP 客户端与 MinIO 容器都在本方法内有界回收；失败只报固定文案，不回显子进程日志或响应原文。
   */
  private void assertNormalWebEntryReadsConvertedData(
      String profile, DataSource target, Path targetKeyFile) throws Exception {
    Path workDir = Files.createDirectory(tempDir.resolve("web-entry-work"));
    Path skillCache = Files.createDirectory(tempDir.resolve("web-entry-skill-cache"));
    try (UpgradeMinioFixture minio = UpgradeMinioFixture.start();
        RunningWebEntry entry =
            RunningWebEntry.start(target, targetKeyFile, workDir, skillCache, minio)) {
      entry.awaitReady();
      assertServedAgentMetadata(entry);
      assertServedEnvironmentMetadata(entry, profile);
      assertServedSkillPackageMetadata(entry);
    }
  }

  /** Agent 列表必须返回转换后的遗留 Agent 元数据。 */
  private static void assertServedAgentMetadata(RunningWebEntry entry) throws Exception {
    JsonNode agents = dataOf(entry.get("/api/ai/catalog/agents"), "agents");
    JsonNode agent = findResult(agents.path("results"), "name", LegacyUpgradeSourceFixture.AGENT);
    assertEquals("USER", agent.path("type").asText(), "the served legacy agent must keep its type");
    assertEquals(
        "legacy agent",
        agent.path("description").asText(),
        "the served legacy agent must keep its description");
    assertEquals(
        LegacyUpgradeSourceFixture.PROVIDER + "/" + LegacyUpgradeSourceFixture.MODEL,
        agent.path("model").asText(),
        "the served legacy agent must keep its model reference");
    assertEquals(
        "1", agent.path("version").asText(), "the served legacy agent must keep its version");
  }

  /** Environment 卡片必须返回转换后的 Card 与保留的 READY 宿主 metadata，且绝不回显注册凭据。 */
  private static void assertServedEnvironmentMetadata(RunningWebEntry entry, String profile)
      throws Exception {
    String body = entry.get("/api/harness/environments");
    JsonNode environment =
        findResult(dataOf(body, "environments"), "id", LEGACY_ENVIRONMENT_ID.toString());
    assertEquals(
        "legacy-environment",
        environment.path("name").asText(),
        "the served legacy environment must keep its name");
    // READY 宿主 metadata 来自 legacy-<profile>-ready-metadata.json，断线后仍然保留。
    assertEquals(
        "linux",
        environment.path("operatingSystem").asText(),
        "the served host OS must be preserved");
    assertEquals(
        "Asia/Shanghai",
        environment.path("timeZone").asText(),
        "the served host time zone must be preserved");
    assertEquals(
        "kkdaemon",
        environment.path("userName").asText(),
        "the served host user must be preserved");
    assertEquals(
        "dev".equals(profile) ? "legacy dev host" : "legacy main host",
        environment.path("note").asText(),
        "the served host note must be preserved");
    assertEquals(
        "dev".equals(profile) ? "1.0.10" : "1.0.14",
        environment.path("daemonVersion").asText(),
        "the served daemon version must be preserved");
    assertEquals(
        "linux",
        environment.path("installConfig").path("operatingSystem").asText(),
        "the served install config must be preserved");
    assertEquals(
        "https://legacy-studio.example.invalid",
        environment.path("installConfig").path("daemon").path("studioUrl").asText(),
        "the served install config must be preserved");
    // 列表与普通详情永不返回注册凭据：卡片既不带 token 字段，也不出现 token 原文。
    assertFalse(
        environment.has("registrationToken"),
        "the environment card must not expose the registration token field");
    assertFalse(
        body.contains(LegacyUpgradeSourceFixture.LEGACY_REGISTRATION_TOKEN),
        "the environment card must not expose the registration token value");
  }

  /** Skill Package 列表必须返回转换后的仓库身份与 manifest，且绝不回显访问令牌。 */
  private static void assertServedSkillPackageMetadata(RunningWebEntry entry) throws Exception {
    String body = entry.get("/api/ai/catalog/skill-packages");
    JsonNode skill =
        findResult(dataOf(body, "skill packages"), "packageName", LEGACY_SKILL_PACKAGE);
    assertEquals(
        "legacy skill package",
        skill.path("description").asText(),
        "the served skill package must keep its description");
    assertEquals(
        "https://git.example.invalid/legacy/skill.git",
        skill.path("repositoryUrl").asText(),
        "the served skill package must keep its repository");
    assertEquals(
        "main", skill.path("branch").asText(), "the served skill package must keep its branch");
    assertEquals(
        LEGACY_SKILL_COMMIT,
        skill.path("currentCommit").asText(),
        "the served skill package must keep its published commit");
    assertEquals(
        LEGACY_SKILL_OBSERVED_HEAD,
        skill.path("observedHeadCommit").asText(),
        "the served skill package must keep its observed branch head");
    assertTrue(
        skill.path("hasToken").asBoolean(),
        "the served skill package must report the converted access token as configured");
    assertEquals(
        "UPDATE_AVAILABLE",
        skill.path("checkStatus").asText(),
        "the served skill package must derive its status from the observed head");
    assertEquals(
        "3", skill.path("version").asText(), "the served skill package must keep its version");
    assertFalse(
        body.contains(LegacyReferenceDataFixture.SKILL_TOKEN),
        "the skill package must never expose the access token value");
  }

  /** 解析真实响应的 {@code Result.data}；非 JSON 只报固定文案，绝不回显响应原文。 */
  private static JsonNode dataOf(String body, String what) {
    try {
      return MAPPER.readTree(body).path("data");
    } catch (JacksonException error) {
      return fail("the normal web entry must return JSON for " + what);
    }
  }

  /** 在真实列表结果里按字段值定位一项；缺失只报固定文案。 */
  private static JsonNode findResult(JsonNode results, String field, String value) {
    for (JsonNode result : results) {
      if (value.equals(result.path(field).asText())) {
        return result;
      }
    }
    return fail("the normal web entry must serve " + field + "=" + value);
  }

  /** 提交后的目标必须能被常规读侧读回与源完全一致的 install config（不是镜像 SQL 计数）。 */
  private static void assertTargetInstallConfigMatchesSource(DataSource source, DataSource target)
      throws SQLException {
    PostgresqlEnvironmentRepository environments =
        new PostgresqlEnvironmentRepository(
            new EnvironmentJdbcStore(JdbcClient.create(target)),
            new PostgresqlEnvironmentChangeNotifier(new JdbcTemplate(target)));
    assertEquals(
        EnvironmentInstallConfigs.read(
            UpgradePostgresTestSupport.text(
                source, "select install_config::text from environment")),
        environments.getById(LEGACY_ENVIRONMENT_ID).getInstallConfig());
  }

  /** 源库逐表规范值摘要：在同一个真实连接上固定 UTC 与 ISO DateStyle，再对每张表取整表行文本的 md5；行数变化或任何列值改动都会改变摘要。 */
  private static Map<String, String> sourceDigests(DataSource source, String profile)
      throws SQLException {
    Map<String, String> digests = new LinkedHashMap<>();
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("set local time zone 'UTC'");
        statement.execute("set local datestyle = 'ISO, MDY'");
        for (String table : digestTables(profile)) {
          try (ResultSet rows = statement.executeQuery(TABLE_DIGEST.formatted(table))) {
            rows.next();
            digests.put(table, rows.getString(1));
          }
        }
      }
      connection.rollback();
    }
    return digests;
  }

  /** 摘要覆盖的源表：全部共有表加 Flyway 历史；MAIN 源另有只存在于该基线的 cluster_node。 */
  private static List<String> digestTables(String profile) {
    List<String> tables = new ArrayList<>(LegacySchemaProfile.SHARED_TABLES);
    tables.add(FLYWAY_HISTORY_TABLE);
    if ("main".equals(profile)) {
      tables.add("cluster_node");
    }
    return tables;
  }

  /** 冻结 profile 约束 schema 与基线历史身份；行值是否改变由逐表摘要单独验证。 */
  private static String sourceProfile(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection()) {
      return LegacySchemaProfile.identify(LegacySchemaProfile.fingerprint(connection));
    }
  }

  /** 解析固定输出里的 {@code table.<name>=<count>} 行。 */
  private static Map<String, Long> rowCounts(String out) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String line : out.lines().toList()) {
      if (line.startsWith("table.")) {
        int separator = line.indexOf('=');
        counts.put(
            line.substring("table.".length(), separator),
            Long.valueOf(line.substring(separator + 1)));
      }
    }
    return counts;
  }

  private static String prepareJson(DataSource target) {
    SimpleDriverDataSource handle = (SimpleDriverDataSource) target;
    return UpgradeCliInputFixture.prepareConfig(
        handle.getUrl(), handle.getUsername(), handle.getPassword());
  }

  private static String convertJson(
      DataSource source, DataSource target, Path sourceKeyFile, Path targetKeyFile) {
    SimpleDriverDataSource sourceHandle = (SimpleDriverDataSource) source;
    SimpleDriverDataSource targetHandle = (SimpleDriverDataSource) target;
    return "{\"source\":"
        + UpgradeCliInputFixture.endpoint(
            sourceHandle.getUrl(), sourceHandle.getUsername(), sourceHandle.getPassword())
        + ",\"target\":"
        + UpgradeCliInputFixture.endpoint(
            targetHandle.getUrl(), targetHandle.getUsername(), targetHandle.getPassword())
        + ",\"sourceKeyFile\":\""
        + sourceKeyFile
        + "\",\"targetKeyFile\":\""
        + targetKeyFile
        + "\",\"staticSecrets\":{\"comfyui\":{\"apiKey\":\""
        + LegacyUpgradeSourceFixture.COMFYUI_API_KEY
        + "\"}}}";
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }

  private ProcessResult runSubprocess(String... args) throws IOException, InterruptedException {
    Path outFile = Files.createTempFile(tempDir, "child-out-", ".log");
    Path errFile = Files.createTempFile(tempDir, "child-err-", ".log");
    ProcessBuilder builder = new ProcessBuilder(childCommand(args));
    removeJvmOptionVariables(builder.environment());
    builder.redirectOutput(outFile.toFile());
    builder.redirectError(errFile.toFile());
    Process process = builder.start();
    try {
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("the offline CLI subprocess did not finish in time");
      }
    } finally {
      // 超时、中断或断言失败都必须回收本次启动的那一个进程，绝不留存活子进程。
      if (process.isAlive()) {
        process.destroyForcibly();
        awaitShutdown(process);
      }
    }
    return new ProcessResult(
        process.exitValue(),
        Files.readString(outFile, StandardCharsets.UTF_8),
        Files.readString(errFile, StandardCharsets.UTF_8));
  }

  /** 本次子进程的完整命令行：显式非 /tmp 的临时目录 + 当前入口模式 + 调用方参数。 */
  private static List<String> childCommand(String... args) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
    command.addAll(entryPoint());
    command.addAll(List.of(args));
    return command;
  }

  /** 移除三类 JVM 启动变量：它们会在 Java 代码执行前向 stderr 打印提示，生产不依赖这些变量。 */
  private static void removeJvmOptionVariables(Map<String, String> environment) {
    for (String variable : JVM_OPTION_ENV_VARIABLES) {
      environment.remove(variable);
    }
  }

  /** 回环上的空闲端口：正常入口只绑定本机回环，绝不占用固定共享端口。 */
  private static int freeLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  /** 有界等待被强杀的进程退出；中断只被推迟到回收结束，绝不吞掉中断语义。 */
  private static void awaitShutdown(Process process) {
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_SHUTDOWN_SECONDS);
    try {
      while (process.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          assertFalse(process.isAlive(), "the subprocess survived forced termination");
          break;
        }
        try {
          process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * 子进程入口：默认优先使用 Surefire 暴露的真实测试 classpath（退化到当前 JVM 的 classpath）剔除测试类目录后直接运行 {@link
   * WebApplication}；提供 {@code -Dkkstudio.upgrade.test.jar=/abs/path.jar} 时改为运行该产物本身，并先验证它是绝对路径上的普通
   * JAR。
   */
  private static List<String> entryPoint() {
    String jar = System.getProperty(TEST_JAR_PROPERTY);
    if (jar == null || jar.isBlank()) {
      String classpath = System.getProperty("surefire.test.class.path");
      String effective =
          classpath != null && !classpath.isBlank()
              ? classpath
              : System.getProperty("java.class.path");
      return List.of("-cp", productionClasspath(effective), WebApplication.class.getName());
    }
    Path path = Path.of(jar);
    assertTrue(path.isAbsolute(), () -> TEST_JAR_PROPERTY + " must be an absolute path: " + jar);
    assertTrue(
        Files.isRegularFile(path),
        () -> TEST_JAR_PROPERTY + " must be an existing regular file: " + jar);
    assertTrue(jar.endsWith(".jar"), () -> TEST_JAR_PROPERTY + " must point at a jar: " + jar);
    return List.of("-jar", path.toString());
  }

  /**
   * 只保留生产 classpath：剔除全部模块的 {@code target/test-classes} 目录。测试类目录里的 {@code WebTestApplication}
   * 与生产入口同包， 留在 classpath 上会被组合根扫描并悄悄把 S3 假件注入生产启动；条目按实际值规范化为绝对路径后再判定，判定前先证明确实过滤掉了测试类目录。
   */
  private static String productionClasspath(String classpath) {
    List<Path> entries =
        Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !entry.isBlank())
            .map(entry -> Path.of(entry).toAbsolutePath().normalize())
            .toList();
    List<Path> production =
        entries.stream().filter(path -> !path.endsWith(TEST_CLASSES_DIRECTORY)).toList();
    assertTrue(
        production.size() < entries.size(),
        "the production classpath must drop the test class directories");
    assertTrue(
        production.stream().noneMatch(path -> path.endsWith(TEST_CLASSES_DIRECTORY)),
        "the production classpath must not contain a test class directory");
    return production.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }

  /**
   * 本次启动的正常生产入口：只拥有自己那一个子进程、自己的 HTTP 客户端与它独占的 executor，{@link #close()} 有界回收全部资源。
   *
   * <p>就绪判定用现成就绪 API（{@code /actuator/health}，含数据库与 S3 探针），读回走真实业务端点；失败只报固定文案，不回显子进程日志或 响应原文。
   */
  private static final class RunningWebEntry implements AutoCloseable {

    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(500);

    private static final String HEALTHY_BODY = "\"status\":\"UP\"";

    private final Process process;

    private final int port;

    private final ExecutorService httpExecutor;

    private final HttpClient http;

    private RunningWebEntry(Process process, int port) {
      this.process = process;
      this.port = port;
      this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
      this.http =
          HttpClient.newBuilder()
              .executor(httpExecutor)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
    }

    /**
     * 启动只绑定回环随机端口的正常入口：prod profile、当前 V1 Flyway、无 worker、私有 S3 与目标主密钥，工作目录与资源缓存都在私有临时目录内。
     *
     * <p>数据面连接与凭据只通过子进程环境变量给出，不进命令行；子进程工作目录由调用方提供，输出写私有文件避免管道写满。
     */
    static RunningWebEntry start(
        DataSource target,
        Path targetKeyFile,
        Path workDir,
        Path skillCacheDir,
        UpgradeMinioFixture minio)
        throws IOException {
      SimpleDriverDataSource handle = (SimpleDriverDataSource) target;
      int port = freeLoopbackPort();
      ProcessBuilder builder =
          new ProcessBuilder(
              childCommand(
                  "--spring.profiles.active=prod",
                  "--server.address=127.0.0.1",
                  "--server.port=" + port,
                  "--kk-studio.harness.runtime.workers-enabled=false",
                  "--kk-studio.storage.maintenance.poll-delay=1h",
                  "--kk-studio.plugins.refresh.poll-delay=1h",
                  "--kk-studio.catalog.skill.cache-root=" + skillCacheDir,
                  "--kk-studio.plugins.credential-key-file=" + targetKeyFile));
      builder.directory(workDir.toFile());
      Map<String, String> environment = builder.environment();
      environment.put("KK_STUDIO_DB_URL", handle.getUrl());
      environment.put("KK_STUDIO_DB_USER", handle.getUsername());
      environment.put("KK_STUDIO_DB_PASSWORD", handle.getPassword());
      environment.put("KK_STUDIO_STORAGE_S3_ENDPOINT", minio.endpoint());
      environment.put("KK_STUDIO_STORAGE_S3_REGION", minio.region());
      environment.put("KK_STUDIO_STORAGE_S3_BUCKET", minio.bucket());
      environment.put("KK_STUDIO_STORAGE_S3_ACCESS_KEY", minio.accessKey());
      environment.put("KK_STUDIO_STORAGE_S3_SECRET_KEY", minio.secretKey());
      removeJvmOptionVariables(environment);
      builder.redirectOutput(Files.createTempFile(workDir, "web-entry-out-", ".log").toFile());
      builder.redirectError(Files.createTempFile(workDir, "web-entry-err-", ".log").toFile());
      return new RunningWebEntry(builder.start(), port);
    }

    /** 有界等待现成就绪 API 报 UP；子进程提前退出立即失败，不把预算浪费在已经死掉的进程上。 */
    void awaitReady() throws Exception {
      URI health = URI.create("http://127.0.0.1:" + port + "/actuator/health");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WEB_ENTRY_READY_TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        assertTrue(process.isAlive(), "the normal web entry exited before it became ready");
        if (isHealthy(health)) {
          return;
        }
        Thread.sleep(READY_POLL_INTERVAL.toMillis());
      }
      fail("the normal web entry did not become ready in time");
    }

    /** 连接被拒或尚未开始监听都只表示「还没就绪」，在预算内继续轮询；中断照常向上传播。 */
    private boolean isHealthy(URI health) throws InterruptedException {
      try {
        HttpResponse<String> response =
            http.send(
                HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains(HEALTHY_BODY);
      } catch (IOException notReadyYet) {
        return false;
      }
    }

    /** 真实 HTTP 读回一个业务只读端点的原始响应体；非 200 以固定文案失败，绝不回显响应原文。 */
    String get(String path) throws Exception {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), "the normal web entry must serve " + path);
      return response.body();
    }

    @Override
    public void close() {
      try {
        http.close();
      } finally {
        httpExecutor.shutdownNow();
        awaitHttpExecutorShutdown();
        if (process.isAlive()) {
          process.destroyForcibly();
        }
        awaitShutdown(process);
      }
    }

    /** 有界等待自有 HTTP executor 终止；中断只被推迟到回收结束，绝不吞掉中断语义。 */
    private void awaitHttpExecutorShutdown() {
      boolean interrupted = Thread.interrupted();
      try {
        httpExecutor.awaitTermination(CHILD_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        interrupted = true;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      assertTrue(httpExecutor.isTerminated(), "the normal web entry HTTP executor must terminate");
    }
  }
}
