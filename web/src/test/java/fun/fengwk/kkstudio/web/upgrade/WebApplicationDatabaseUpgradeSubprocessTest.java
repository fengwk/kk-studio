package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.web.WebApplication;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 真实子进程的 {@code WebApplication} 入口：首参 {@code database-upgrade} 必须走离线 CLI 且不启动 Spring
 * Web/Worker，帮助、用法错误、真实空库 prepare、真实连接失败与 DEV/MAIN 的完整 prepare→dry-run→apply→再 apply 都在独立 JVM
 * 里以进程退出码与输出呈现。
 *
 * <p>子进程继承的 JVM 环境会污染 stderr（如 {@code JAVA_TOOL_OPTIONS} 的 "Picked up" 提示），因此启动前从子环境移除这些变量并显式传入非
 * /tmp 的临时目录；输出重定向到私有文件后再读取，避免管道写满造成死锁。stderr 断言保持严格：任何额外的诊断行都会失败。
 *
 * <p>默认用当前测试 classpath 直接运行 {@link WebApplication}；提供 {@code
 * -Dkkstudio.upgrade.test.jar=/abs/path.jar} 时改为运行该 Fat JAR 产物本身，因此同一批用例可以在打包后原样复用（打包产物的验收由 parent
 * 在集成阶段执行）。
 *
 * <p>用例只连接可丢弃的隔离容器库，不连接任何实际部署。
 */
class WebApplicationDatabaseUpgradeSubprocessTest {

  private static final long TIMEOUT_SECONDS = 120;

  private static final String SENTINEL = "sentinel-subprocess-credential-3312";

  /** 指向待验收的 Fat JAR；未设置时用当前测试 classpath 直接运行入口类。 */
  private static final String TEST_JAR_PROPERTY = "kkstudio.upgrade.test.jar";

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

  /** DEV 源在独立 JVM 里走完整链路：prepare 初始化空目标，dry-run 回滚，apply 提交，再 apply 被拒绝，源库不变。 */
  @Test
  void devSourceRunsPrepareDryRunApplyAndReapplyRefusal() throws Exception {
    assertUpgradeCycle("dev");
  }

  /** MAIN 源与 DEV 共用同一条真实子进程链路与全部断言。 */
  @Test
  void mainSourceRunsPrepareDryRunApplyAndReapplyRefusal() throws Exception {
    assertUpgradeCycle("main");
  }

  private void assertUpgradeCycle(String profile) throws Exception {
    SecretKey sourceKey = key(0x2a);
    SecretKey targetKey = key(0x3b);
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(profile);
    LegacyUpgradeSourceFixture.load(source, sourceKey);
    LegacyHarnessHistoryFixture.insert(source);
    DataSource target = UpgradePostgresTestSupport.blankDatabase();
    Map<String, Long> sourceFacts = sourceFacts(source);

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
    Map<String, Long> dryRunCounts = rowCounts(dryRun.out());
    assertFalse(dryRunCounts.isEmpty(), dryRun.out());
    // dry-run 必须整体回滚：目标回到 pristine。
    UpgradePostgresTestSupport.assertPristine(target);

    ProcessResult apply =
        runSubprocess("database-upgrade", "apply", "--config", convertConfig.toString());
    assertEquals(0, apply.exit(), apply.err());
    assertTrue(apply.out().contains("mode=apply"));
    assertTrue(apply.out().contains("profile=" + profile));
    assertTrue(apply.out().contains("applied=true"));
    // apply 与 dry-run 必须逐表一致，且提交后的目标能被常规读取。
    assertEquals(dryRunCounts, rowCounts(apply.out()));
    for (Map.Entry<String, Long> row : dryRunCounts.entrySet()) {
      assertEquals(
          row.getValue().longValue(),
          UpgradePostgresTestSupport.count(target, row.getKey()),
          row.getKey());
    }

    // 源库完全不变：仍是同一个冻结 profile，且常规读取事实逐一相同。
    assertEquals(profile, sourceProfile(source));
    assertEquals(sourceFacts, sourceFacts(source));

    // 已提交的目标不再是 pristine：同一份配置再 apply 必须拒绝而不是覆盖或重试。
    ProcessResult reapply =
        runSubprocess("database-upgrade", "apply", "--config", convertConfig.toString());
    assertEquals(1, reapply.exit());
    assertTrue(reapply.err().contains("error.stage=TARGET_PRISTINE"), reapply.err());
    assertEquals("", reapply.out());
  }

  /** 源库的常规读取事实：关键表的真实行数，前后相等即证明 CLI 不改源。 */
  private static Map<String, Long> sourceFacts(DataSource source) throws SQLException {
    Map<String, Long> facts = new LinkedHashMap<>();
    facts.put("agent_provider", UpgradePostgresTestSupport.count(source, "agent_provider"));
    facts.put("plugin_credential", UpgradePostgresTestSupport.count(source, "plugin_credential"));
    facts.put("system_setting", UpgradePostgresTestSupport.count(source, "system_setting"));
    return facts;
  }

  /** 源库的可观察身份：冻结 profile 指纹，任何源端改动都会让它变化。 */
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
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
    command.addAll(entryPoint());
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    // 继承的启动变量会在 Java 代码执行前向 stderr 打印提示，必须从子环境移除；生产不依赖这些变量。
    for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
      builder.environment().remove(variable);
    }
    builder.redirectOutput(outFile.toFile());
    builder.redirectError(errFile.toFile());
    Process process = builder.start();
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("the offline CLI subprocess did not finish in time");
    }
    return new ProcessResult(
        process.exitValue(),
        Files.readString(outFile, StandardCharsets.UTF_8),
        Files.readString(errFile, StandardCharsets.UTF_8));
  }

  /**
   * 子进程入口：默认优先使用 Surefire 暴露的真实测试 classpath（退化到当前 JVM 的 classpath）直接运行 {@link WebApplication}；提供
   * {@code -Dkkstudio.upgrade.test.jar=/abs/path.jar} 时改为运行该产物本身，并先验证它是绝对路径上的普通 JAR。
   */
  private static List<String> entryPoint() {
    String jar = System.getProperty(TEST_JAR_PROPERTY);
    if (jar == null || jar.isBlank()) {
      String classpath = System.getProperty("surefire.test.class.path");
      String effective =
          classpath != null && !classpath.isBlank()
              ? classpath
              : System.getProperty("java.class.path");
      return List.of("-cp", effective, WebApplication.class.getName());
    }
    Path path = Path.of(jar);
    assertTrue(path.isAbsolute(), () -> TEST_JAR_PROPERTY + " must be an absolute path: " + jar);
    assertTrue(
        Files.isRegularFile(path),
        () -> TEST_JAR_PROPERTY + " must be an existing regular file: " + jar);
    assertTrue(jar.endsWith(".jar"), () -> TEST_JAR_PROPERTY + " must point at a jar: " + jar);
    return List.of("-jar", path.toString());
  }
}
