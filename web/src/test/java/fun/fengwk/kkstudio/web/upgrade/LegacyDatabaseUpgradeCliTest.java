package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * 受保护 CLI 的纯输入/参数边界：完整参数、严格 JSON、key 文件权限与形态、安全输出与退出码，不连接任何数据库。
 *
 * <p>「输入被接受」的用例仍走真实 {@code SimpleDriverDataSource}，因此验证的是输入解析后安全 fail closed 且不回显凭据，而不是 mock 的转换器。
 *
 * <p>CLI 会在读 secret 前关闭本 JVM 的全部现存 logger、根日志与 JUL（独占一次性进程语义）；日志关闭断言观察的是本进程真实 SLF4J
 * 后端的启用决策，其余用例只断言输出流本身。
 */
class LegacyDatabaseUpgradeCliTest {

  private static final String UNREACHABLE = "jdbc:postgresql://127.0.0.1:1/kk_studio_missing";

  private static final String UNREACHABLE_TARGET = "jdbc:postgresql://127.0.0.1:1/kk_studio_target";

  private static final String SENTINEL = "sentinel-value-do-not-leak-5142";

  /** 日志关闭回归的探针：一个被显式置为 DEBUG 的命名 logger；只关根日志时它仍会输出。 */
  private static final String DEBUG_PROBE_LOGGER = "fun.fengwk.kkstudio.web.upgrade.cli.probe";

  @TempDir Path tempDir;

  /** 一次 CLI 调用的退出码与两条输出流。 */
  private record Run(int exit, String out, String err) {}

  @Test
  void helpPrintsUsageAndExitsZero() {
    Run result = run("--help");
    assertEquals(0, result.exit());
    assertTrue(result.out().contains("database-upgrade"));
    assertTrue(result.out().contains("prepare"));
    assertTrue(result.out().contains("--config"));
    assertEquals("", result.err());
  }

  @Test
  void helpWithExtraArgumentsIsRejected() {
    Run result = run("--help", "extra");
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=USAGE"));
  }

  /**
   * 真实 CLI 入口的日志关闭回归：显式 DEBUG 的命名 logger 在 {@code --help} 边界之后也必须关闭。
   *
   * <p>断言的是本进程真实 SLF4J 后端（探针 logger 由该后端自己接受 DEBUG）的启用决策，而不是任何 Spring 配置值。
   */
  @Test
  void helpTurnsOffAnExplicitlyDebugNamedLogger() {
    configureDebugProbeLogger();

    Run result = run("--help");

    assertEquals(0, result.exit());
    assertFalse(
        LoggerFactory.getLogger(DEBUG_PROBE_LOGGER).isDebugEnabled(),
        "an explicitly DEBUG named logger must be off after the CLI ran");
    assertFalse(
        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).isDebugEnabled(),
        "the root logger must be off after the CLI ran");
  }

  /** {@code LoggingSystem=none} 不能把关闭变成 no-op：真实 classpath 后端仍必须被选中并关闭。 */
  @Test
  void helpTurnsOffLoggingWhenTheLoggingSystemIsConfiguredAsNone() {
    String previous = System.getProperty(LoggingSystem.SYSTEM_PROPERTY);
    System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
    try {
      configureDebugProbeLogger();

      Run result = run("--help");

      assertEquals(0, result.exit());
      assertFalse(
          LoggerFactory.getLogger(DEBUG_PROBE_LOGGER).isDebugEnabled(),
          "LoggingSystem=none must not turn the diagnostic logging shutdown into a no-op");
    } finally {
      if (previous == null) {
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
      } else {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, previous);
      }
    }
  }

  /** 用 Spring Boot 的通用后端选择把探针 logger 显式置为 DEBUG，并断言真实后端确实接受它；否则随后的关闭断言没有意义。 */
  private static void configureDebugProbeLogger() {
    LoggingSystem loggingSystem =
        LoggingSystemFactory.fromSpringFactories()
            .getLoggingSystem(LegacyDatabaseUpgradeCliTest.class.getClassLoader());
    assertNotNull(loggingSystem, "the test classpath must provide a real logging backend");
    loggingSystem.setLogLevel(DEBUG_PROBE_LOGGER, LogLevel.DEBUG);
    assertTrue(
        LoggerFactory.getLogger(DEBUG_PROBE_LOGGER).isDebugEnabled(),
        "the real logging backend must honour an explicitly configured DEBUG logger");
  }

  @Test
  void missingCommandIsRejected() {
    assertEquals(2, run().exit());
  }

  @Test
  void unknownCommandIsRejected() {
    Run result = run("migrate", "--config", "/tmp/x.json");
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.type=UsageError"));
  }

  @Test
  void missingConfigValueIsRejected() {
    assertEquals(2, run("apply").exit());
    assertEquals(2, run("apply", "--config").exit());
  }

  @Test
  void wrongOrExtraOptionsAreRejected() {
    assertEquals(2, run("apply", "--file", "/tmp/x.json").exit());
    assertEquals(2, run("apply", "--config", "/tmp/x.json", "extra").exit());
    assertEquals(2, run("apply", "--config", "/tmp/x.json", "--config", "/tmp/y.json").exit());
  }

  @Test
  void relativeConfigPathIsRejectedAsUsage() {
    Run result = run("apply", "--config", "relative.json");
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=USAGE"));
  }

  /** 含 NUL 的路径无法变成 Path，必须在读取任何文件前按用法拒绝。 */
  @Test
  void unparsableConfigPathIsRejectedAsUsage() {
    Run result = run("apply", "--config", "/tmp/upgrade" + ((char) 0) + ".json");
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=USAGE"));
  }

  @Test
  void missingConfigFileIsRejected() {
    Run result = run("prepare", "--config", tempDir.resolve("absent.json").toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
  }

  @Test
  void configDirectoryIsRejected() throws IOException {
    Run result = run("prepare", "--config", dir().toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
  }

  @Test
  void groupReadableConfigIsRejected() throws IOException {
    Path config =
        UpgradeCliInputFixture.groupReadableConfig(
            dir(), "config.json", UpgradeCliInputFixture.prepareConfig(UNREACHABLE));
    Run result = run("prepare", "--config", config.toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
  }

  @Test
  void symlinkedConfigIsRejected() throws IOException {
    Path real = configFile(UpgradeCliInputFixture.prepareConfig(UNREACHABLE));
    Path link = dir().resolve("link.json");
    Files.createSymbolicLink(link, real);
    Run result = run("prepare", "--config", link.toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
  }

  /** 不支持 POSIX 的文件系统必须在读取前 fail closed；用 zipfs 直接验证受保护文件门禁。 */
  @Test
  void nonPosixFileSystemFailsClosed() throws IOException {
    Path zip = tempDir.resolve("input.zip");
    URI uri = URI.create("jar:" + zip.toUri());
    try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
      Path config = fileSystem.getPath("/config.json");
      Files.writeString(config, UpgradeCliInputFixture.prepareConfig(UNREACHABLE));
      LegacyUpgradeCliException error =
          assertThrows(
              LegacyUpgradeCliException.class, () -> LegacyUpgradeConfig.read(true, config));
      assertEquals(LegacyUpgradeCliException.Stage.INPUT, error.stage());
      assertTrue(error.getMessage().contains("POSIX"));
    }
  }

  @Test
  void oversizedConfigIsRejected() throws IOException {
    byte[] oversized = new byte[LegacyUpgradeConfig.MAX_CONFIG_BYTES + 16];
    Path config = UpgradeCliInputFixture.ownerOnlyFile(dir(), "config.json", oversized);
    Run result = run("prepare", "--config", config.toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
  }

  @Test
  void invalidJsonFormsAreRejected() throws IOException {
    assertPrepareRejected("{");
    assertPrepareRejected("[1,2]");
    assertPrepareRejected("null");
    assertPrepareRejected("{} {}");
    assertPrepareRejected("{\"target\":{},\"target\":{}}");
    assertPrepareRejected("{}");
  }

  @Test
  void unknownFieldsAreRejectedWithoutEchoingTheKeyOrValue() throws IOException {
    String config =
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"secret-field\":\"" + SENTINEL + "\"}}";
    Run result = run("prepare", "--config", configFile(config).toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
    assertFalse(result.err().contains("secret-field"));
    assertFalse(result.err().contains(SENTINEL));
  }

  @Test
  void prepareRejectsSourceKeyAndMaterialFields() throws IOException {
    String target = "\"target\":" + UpgradeCliInputFixture.endpoint(UNREACHABLE);
    assertPrepareRejected("{" + target + ",\"source\":[]}");
    assertPrepareRejected("{" + target + ",\"sourceKeyFile\":\"/tmp/k\"}");
    assertPrepareRejected("{" + target + ",\"staticSecrets\":{\"comfyui\":{\"apiKey\":\"x\"}}}");
  }

  @Test
  void endpointShapeIsValidated() throws IOException {
    assertPrepareRejected("{\"target\":123}");
    assertPrepareRejected("{\"target\":null}");
    assertPrepareRejected("{\"target\":[]}");
    assertPrepareRejected("{\"target\":{\"url\":123,\"username\":\"u\",\"password\":\"p\"}}");
    assertPrepareRejected("{\"target\":{\"url\":\"\",\"username\":\"u\",\"password\":\"p\"}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\"mysql://x\",\"username\":\"u\",\"password\":\"p\"}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":\"  \",\"password\":\"p\"}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":123,\"password\":\"p\"}}");
    assertPrepareRejected("{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":\"u\"}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":\"u\",\"password\":1}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":\"u\",\"password\":null}}");
    assertPrepareRejected(
        "{\"target\":{\"url\":\""
            + UNREACHABLE
            + "\",\"username\":\"u\",\"password\":\"p\",\"database\":\"d\"}}");
  }

  @Test
  void dryRunRequiresSource() throws IOException {
    assertDryRunRejected(UpgradeCliInputFixture.prepareConfig(UNREACHABLE));
  }

  @Test
  void staticSecretsShapeIsValidated() throws IOException {
    String base =
        "{\"source\":"
            + UpgradeCliInputFixture.endpoint(UNREACHABLE)
            + ",\"target\":"
            + UpgradeCliInputFixture.endpoint(UNREACHABLE_TARGET)
            + ",\"staticSecrets\":";
    assertDryRunRejected(base + "[]}");
    assertDryRunRejected(base + "{\"comfyui\":\"x\"}}");
    assertDryRunRejected(base + "{\"comfyui\":{\"apiKey\":1}}}");
    assertDryRunRejected(base + "{\"comfyui\":{}}}");
  }

  @Test
  void keyFileShapeAndPermissionsAreValidated() throws IOException {
    byte[] valid = new byte[32];
    assertDryRunRejected(dryRunWithKeys("key.bin", null));
    assertDryRunRejected(dryRunWithKeys(tempDir.resolve("absent.key").toString(), null));

    Path real = UpgradeCliInputFixture.key(dir(), "real.key", valid);
    Path link = dir().resolve("link.key");
    Files.createSymbolicLink(link, real);
    assertDryRunRejected(dryRunWithKeys(link.toString(), null));

    Path wide = UpgradeCliInputFixture.key(dir(), "wide.key", valid);
    Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rw-r--r--"));
    assertDryRunRejected(dryRunWithKeys(wide.toString(), null));

    Path shortKey = UpgradeCliInputFixture.key(dir(), "short.key", new byte[31]);
    assertDryRunRejected(dryRunWithKeys(shortKey.toString(), null));

    Path directoryKey = dir().resolve("keydir");
    Files.createDirectory(directoryKey);
    assertDryRunRejected(dryRunWithKeys(directoryKey.toString(), null));
  }

  /** 主密钥必须恰为 32 bytes raw AES-256：多一个字节也不能被截断接受，两个 key 字段都要按同一规则拒绝。 */
  @Test
  void thirtyThreeByteKeyIsRejectedOnBothKeyFields() throws IOException {
    Path longKey = UpgradeCliInputFixture.key(dir(), "long.key", new byte[33]);

    Run source =
        run("dry-run", "--config", configFile(dryRunWithKeys(longKey.toString(), null)).toString());
    assertEquals(2, source.exit());
    assertTrue(source.err().contains("must contain exactly 32 bytes"));

    Run target =
        run("dry-run", "--config", configFile(dryRunWithKeys(null, longKey.toString())).toString());
    assertEquals(2, target.exit());
    assertTrue(target.err().contains("must contain exactly 32 bytes"));
  }

  /** 严格 UTF-8 解码：非法字节序列必须在解析 JSON 之前按输入形态拒绝，且不回显任何原文。 */
  @Test
  void malformedUtf8ConfigIsRejected() throws IOException {
    byte[] malformed = new byte[] {'{', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
    Path config = UpgradeCliInputFixture.ownerOnlyFile(dir(), "config.json", malformed);
    Run result = run("prepare", "--config", config.toString());
    assertEquals(2, result.exit());
    assertTrue(result.err().contains("error.stage=INPUT"));
    assertTrue(result.err().contains("UTF-8"));
  }

  @Test
  void keyFilePathMustBeAString() throws IOException {
    String config =
        "{\"source\":"
            + UpgradeCliInputFixture.endpoint(UNREACHABLE)
            + ",\"target\":"
            + UpgradeCliInputFixture.endpoint(UNREACHABLE_TARGET)
            + ",\"sourceKeyFile\":123}";
    assertDryRunRejected(config);
  }

  /** 输入被接受后仍要安全 fail closed：真实连接失败只输出固定阶段，且绝不回显连接或密钥材料。 */
  @Test
  void acceptedInputFailsClosedWithoutLeakingSecrets() throws IOException {
    Path sourceKey = UpgradeCliInputFixture.key(dir(), "source.key", new byte[32]);
    Path targetKey = UpgradeCliInputFixture.key(dir(), "target.key", new byte[32]);
    String config =
        "{\"source\":"
            + endpoint(UNREACHABLE, SENTINEL)
            + ",\"target\":"
            + endpoint(UNREACHABLE_TARGET, SENTINEL)
            + ",\"sourceKeyFile\":\""
            + sourceKey
            + "\",\"targetKeyFile\":\""
            + targetKey
            + "\",\"staticSecrets\":{\"comfyui\":{\"apiKey\":\""
            + SENTINEL
            + "\"}}}";
    Run result = run("dry-run", "--config", configFile(config).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CONNECTION"));
    assertTrue(result.err().contains("error.type=LegacyDatabaseUpgradeException"));
    assertTrue(result.err().contains("outcomeUncertain=false"));
    assertFalse(result.out().contains(SENTINEL));
    assertFalse(result.err().contains(SENTINEL));
    assertFalse(result.err().contains("127.0.0.1"));
    assertFalse(result.err().contains("kk_studio_missing"));
  }

  /** 空密码是合法输入（允许空字符串），因此会走到真实连接而不是输入拒绝。 */
  @Test
  void emptyPasswordIsAcceptedAsInput() throws IOException {
    String config =
        "{\"target\":{\"url\":\"" + UNREACHABLE + "\",\"username\":\"u\",\"password\":\"\"}}";
    Run result = run("prepare", "--config", configFile(config).toString());
    assertEquals(1, result.exit());
    assertTrue(result.err().contains("error.stage=CONNECTION"));
  }

  private String dryRunWithKeys(String sourceKeyFile, String targetKeyFile) {
    StringBuilder config =
        new StringBuilder("{\"source\":")
            .append(UpgradeCliInputFixture.endpoint(UNREACHABLE))
            .append(",\"target\":")
            .append(UpgradeCliInputFixture.endpoint(UNREACHABLE_TARGET));
    if (sourceKeyFile != null) {
      config.append(",\"sourceKeyFile\":\"").append(sourceKeyFile).append('"');
    }
    if (targetKeyFile != null) {
      config.append(",\"targetKeyFile\":\"").append(targetKeyFile).append('"');
    }
    return config.append('}').toString();
  }

  private void assertPrepareRejected(String config) throws IOException {
    assertInputRejected("prepare", config);
  }

  private void assertDryRunRejected(String config) throws IOException {
    assertInputRejected("dry-run", config);
  }

  private void assertInputRejected(String command, String config) throws IOException {
    Run result = run(command, "--config", configFile(config).toString());
    assertEquals(2, result.exit(), () -> "expected input rejection for: " + config);
    assertTrue(
        result.err().contains("error.stage=INPUT"), () -> "unexpected error: " + result.err());
  }

  private static String endpoint(String url, String password) {
    return "{\"url\":\""
        + url
        + "\",\"username\":\""
        + UpgradeCliInputFixture.USERNAME
        + "\",\"password\":\""
        + password
        + "\"}";
  }

  private Path configFile(String content) throws IOException {
    return UpgradeCliInputFixture.config(dir(), "config.json", content);
  }

  private Path dir() throws IOException {
    return UpgradeCliInputFixture.ownerOnlyDir(tempDir);
  }

  private static Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      exit = LegacyDatabaseUpgradeCli.run(args, outStream, errStream);
    }
    return new Run(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
}
