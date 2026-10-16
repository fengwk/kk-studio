package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.web.WebApplication;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实子进程的 {@code WebApplication} 入口：首参 {@code database-upgrade} 必须走离线 CLI 且不启动 Spring
 * Web/Worker，帮助、用法错误、真实空库 prepare 与真实连接失败 都在独立 JVM 里以进程退出码与输出呈现。
 *
 * <p>子进程继承的 JVM 环境会污染 stderr（如 {@code JAVA_TOOL_OPTIONS} 的 "Picked up" 提示），因此启动前从子环境移除这些变量并显式传入非
 * /tmp 的临时目录；输出重定向到私有文件后 再读取，避免管道写满造成死锁。stderr 断言保持严格：任何额外的诊断行都会失败。
 *
 * <p>用例只连接可丢弃的隔离容器库，不连接任何实际部署。
 */
class WebApplicationDatabaseUpgradeSubprocessTest {

  private static final long TIMEOUT_SECONDS = 120;

  private static final String SENTINEL = "sentinel-subprocess-credential-3312";

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

  private ProcessResult runSubprocess(String... args) throws IOException, InterruptedException {
    Path outFile = Files.createTempFile(tempDir, "child-out-", ".log");
    Path errFile = Files.createTempFile(tempDir, "child-err-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
    command.add("-cp");
    command.add(testClasspath());
    command.add(WebApplication.class.getName());
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

  /** 优先使用 Surefire 暴露的真实测试 classpath；退化到当前 JVM 的 classpath。 */
  private static String testClasspath() {
    String classpath = System.getProperty("surefire.test.class.path");
    return classpath != null && !classpath.isBlank()
        ? classpath
        : System.getProperty("java.class.path");
  }
}
