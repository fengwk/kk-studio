package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * <p>CLI 会在读 secret 前关闭本 JVM 的根日志（独占一次性进程语义）；本类只断言输出流本身，不依赖日志。
 */
class LegacyDatabaseUpgradeCliTest {

  private static final String UNREACHABLE = "jdbc:postgresql://127.0.0.1:1/kk_studio_missing";

  private static final String UNREACHABLE_TARGET = "jdbc:postgresql://127.0.0.1:1/kk_studio_target";

  private static final String SENTINEL = "sentinel-value-do-not-leak-5142";

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
