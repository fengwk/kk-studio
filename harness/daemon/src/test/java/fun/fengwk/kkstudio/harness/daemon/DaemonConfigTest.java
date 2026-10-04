package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import fun.fengwk.kkstudio.harness.daemon.coding.ExecutableResolver;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 文件为唯一配置来源；派生路径、bash 解析、秘密保护与内部运行时不变量不能因 CLI 收敛而退化。 */
class DaemonConfigTest {
  @TempDir Path root;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void derivesRuntimeFromFileAndFixedSiblingLayout() throws Exception {
    Path file = writeConfig(root, "https://studio.example/");
    var json = (ObjectNode) MAPPER.readTree(file.toFile());
    json.put("note", " Custom local environment. ");
    var server = json.putObject("lsp").putObject("servers").putObject("java");
    server.putArray("command").add("/opt/jdtls/bin/jdtls");
    server.putArray("extensions").add(".JAVA");
    MAPPER.writeValue(file.toFile(), json);
    DaemonConfig config = load(file);
    assertEquals(
        URI.create("wss://studio.example/api/harness/environment-daemon/v1"), config.gatewayUri());
    assertEquals(root.resolve("daemon.token"), config.registrationTokenFile());
    assertEquals(root, config.dataDir());
    assertEquals("secret", config.registrationToken());
    assertEquals(Duration.ofSeconds(15), config.heartbeatInterval());
    assertEquals(Duration.ofSeconds(1), config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(30), config.maxReconnectDelay());
    assertEquals("Custom local environment.", config.note());
    assertEquals(defaultBash(), config.bashExecutable());
    assertEquals(List.of(".java"), config.lsp().servers().getFirst().extensions());
    assertEquals(List.of("daemon.json", "daemon.token"), entries(root));
  }

  @Test
  void decodedUnicodeConfigPathAndValuesArePreserved() throws Exception {
    Path dir = Files.createDirectory(root.resolve("中文数据 😀"));
    Path bash = writeExecutable(dir, "中文工具/bash");
    Path file = writeConfig(dir, "http://localhost");
    var json =
        MAPPER.createObjectNode().put("studioUrl", "http://localhost").put("note", "中文说明 😀");
    json.put("bashExecutable", bash.toString());
    MAPPER.writeValue(file.toFile(), json);
    DaemonConfig config =
        DaemonConfig.fromArgs(
            DaemonArguments.decode(DaemonArgumentsTest.encoded("--config", file.toString())));
    assertEquals(dir, config.dataDir());
    assertEquals("中文说明 😀", config.note());
    assertEquals(bash.toString(), config.bashExecutable());
    assertEquals(List.of("daemon.json", "daemon.token", "中文工具"), entries(dir));
  }

  @Test
  void onlySingleConfigPairIsAcceptedWithoutEchoingArguments() throws Exception {
    Path file = writeConfig(root, "http://localhost");
    for (String[] args :
        new String[][] {
          {},
          {"--config"},
          {"--config", ""},
          {"--config", "relative.json"},
          {"--config", "--help"},
          {"--config", file.toString(), "--config", file.toString()},
          {"--check-config", file.toString()},
          {"--help", "--config", file.toString()},
          {"--config", file.toString(), "SECRET"},
          {"--config", "SECRET\u0000"}
        }) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromArgs(args));
      assertFalse(error.getMessage().contains("SECRET"));
    }
    for (String flag :
        List.of(
            "--gateway-uri",
            "--registration-token-file",
            "--registration-token",
            "--heartbeat",
            "--reconnect-initial",
            "--reconnect-max",
            "--note",
            "--data-dir",
            "--bash-executable",
            "--lsp-config",
            "--skill-dir",
            "--tool-timeout",
            "--environment-root")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonConfig.fromArgs(new String[] {flag, "SECRET"}));
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonConfig.fromArgs(new String[] {"--config", file.toString(), flag, "SECRET"}));
    }
    assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromFile(Path.of("relative")));
    assertThrows(IllegalArgumentException.class, () -> load(root.resolve("missing.json")));
  }

  @Test
  void rejectsFileStructureAndDuplicateKeysThroughSharedCodec() throws Exception {
    Path file = writeConfig(root, "http://localhost");
    for (String json :
        List.of(
            "{\"studioUrl\":\"http://localhost\",\"registrationToken\":\"SECRET\"}",
            "{\"studioUrl\":\"http://localhost\",\"studioUrl\":\"SECRET\"}",
            "{\"studioUrl\":\"http://localhost\",\"lsp\":{\"servers\":{}}}",
            "{\"studioUrl\":\"http://localhost\",\"note\":\"SECRET\\n\"}")) {
      Files.writeString(file, json);
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> load(file));
      assertFalse(error.getMessage().contains("SECRET"));
      assertNull(error.getCause());
    }
  }

  /** 意图：配置覆盖与默认值都必须解析为宿主可执行程序；缺失、不可执行或 PATH 缺失都在启动期失败关闭， 且错误只含字段路径、不回显配置取值。 */
  @Test
  void configuredBashMustResolveToExecutable() throws Exception {
    Path file = writeConfig(root, "http://localhost");

    Path missing = root.resolve("absent-bash");
    writeBash(file, missing.toString());
    IllegalArgumentException missingError =
        assertThrows(IllegalArgumentException.class, () -> load(file));
    assertTrue(
        missingError.getMessage().contains("daemon.bashExecutable"), missingError.getMessage());
    assertFalse(missingError.getMessage().contains(missing.toString()), "错误信息不得回显配置取值");

    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Path plain = Files.writeString(root.resolve("plain-bash"), "not executable");
      Files.setPosixFilePermissions(
          plain, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      writeBash(file, plain.toString());
      assertThrows(IllegalArgumentException.class, () -> load(file));
    }

    Path custom = writeExecutable(root.resolve("bin"), "acme-bash");
    writeBash(file, custom.toString());
    assertEquals(custom.toString(), load(file).bashExecutable());

    writeBash(file, DaemonConfig.DEFAULT_BASH_EXECUTABLE);
    assertEquals(defaultBash(), load(file).bashExecutable());

    writeBash(file, "kk-studio-absent-command-" + System.nanoTime());
    IllegalArgumentException absentError =
        assertThrows(IllegalArgumentException.class, () -> load(file));
    assertTrue(absentError.getMessage().contains("daemon.bashExecutable"));
  }

  @Test
  void tokenRemainsOnDemandAndOutsideRecordSurfaces() throws Exception {
    DaemonConfig first = load(writeConfig(root, "http://localhost"));
    assertFalse(first.toString().contains("secret"));
    Path other = Files.createDirectory(root.resolve("other"));
    DaemonConfig second = load(writeConfig(other, "http://localhost"));
    assertNotEquals(first, second);
    Files.writeString(first.registrationTokenFile(), "rotated-secret");
    assertEquals("rotated-secret", first.registrationToken());
    assertFalse(first.toString().contains("rotated-secret"));
    Files.writeString(first.registrationTokenFile(), "  \n");
    assertThrows(IllegalStateException.class, first::registrationToken);
  }

  @Test
  void tokenMustBeExistingRegularPrivateSibling() throws Exception {
    Path file = writeConfig(root, "http://localhost");
    Path token = file.resolveSibling("daemon.token");
    Files.delete(token);
    assertThrows(IllegalArgumentException.class, () -> load(file));
    Files.createDirectory(token);
    assertThrows(IllegalArgumentException.class, () -> load(file));
    Files.delete(token);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Path target = Files.writeString(root.resolve("target"), "secret");
      Files.createSymbolicLink(token, target);
      assertThrows(IllegalArgumentException.class, () -> load(file));
      Files.delete(token);
      writeToken(root);
      Files.setPosixFilePermissions(
          token, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
      assertThrows(IllegalArgumentException.class, () -> load(file));
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void removedPropertiesDoNotOverrideFile() throws Exception {
    Path file = writeConfig(root, "http://localhost");
    String key = "kkstudio.daemon.bash";
    String previous = System.getProperty(key);
    try {
      System.setProperty(key, "SECRET");
      DaemonConfig config = load(file);
      assertEquals(defaultBash(), config.bashExecutable());
      assertTrue(config.lsp().servers().isEmpty());
      assertNull(config.note());
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void exactDefaultNotesAndExplicitNotePriority() throws Exception {
    Path file = writeConfig(root, "http://localhost");
    DaemonConfig defaults = load(file);
    assertEquals("Windows environment.", defaults.effectiveNote(DaemonOperatingSystem.WINDOWS));
    assertEquals(
        "WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.",
        defaults.effectiveNote(DaemonOperatingSystem.WSL));
    assertEquals("Linux environment.", defaults.effectiveNote(DaemonOperatingSystem.LINUX));
    assertEquals("macOS environment.", defaults.effectiveNote(DaemonOperatingSystem.MACOS));
    Files.writeString(
        file, "{\"studioUrl\":\"http://localhost\",\"note\":\"Explicit environment.\"}");
    for (DaemonOperatingSystem os : DaemonOperatingSystem.values()) {
      assertEquals("Explicit environment.", load(file).effectiveNote(os));
    }
  }

  @Test
  void internalRuntimeConstructorsRetainInvariants() throws Exception {
    Path token = writeToken(root);
    URI gateway = URI.create("ws://localhost/gateway");
    Duration positive = Duration.ofSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(URI.create("http://host"), token, positive, Duration.ZERO, positive, root));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(gateway, token, Duration.ZERO, Duration.ZERO, positive, root));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(gateway, token, positive, Duration.ofSeconds(-1), positive, root));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(gateway, token, positive, Duration.ZERO, Duration.ZERO, root));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(gateway, token, positive, Duration.ofSeconds(2), positive, root));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime(gateway, token, positive, Duration.ZERO, positive, Path.of("relative")));
    assertEquals(
        "secret",
        runtime(gateway, token, positive, Duration.ZERO, positive, root.resolve("not-created"))
            .registrationToken());
    assertFalse(Files.exists(root.resolve("not-created")));
  }

  private static DaemonConfig runtime(
      URI uri, Path token, Duration heartbeat, Duration initial, Duration max, Path data) {
    return new DaemonConfig(uri, token, heartbeat, initial, max, null, data);
  }

  static Path writeConfig(Path directory, String url) throws IOException {
    Path file = directory.resolve("daemon.json");
    MAPPER.writeValue(file.toFile(), MAPPER.createObjectNode().put("studioUrl", url));
    writeToken(directory);
    return file;
  }

  static Path writeToken(Path directory) throws IOException {
    Path file = Files.writeString(directory.resolve("daemon.token"), "secret");
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    return file;
  }

  /** 在给定目录下创建真实可执行脚本，模拟操作者提供的自定义 bash。 */
  static Path writeExecutable(Path directory, String relative) throws IOException {
    Path file = directory.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "#!/bin/sh\nexit 0\n");
    if (!file.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark executable: " + file);
    }
    return file;
  }

  static List<String> entries(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      return files.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static void writeBash(Path file, String bash) throws IOException {
    MAPPER.writeValue(
        file.toFile(),
        MAPPER.createObjectNode().put("studioUrl", "http://localhost").put("bashExecutable", bash));
  }

  private static String defaultBash() {
    return ExecutableResolver.resolve(DaemonConfig.DEFAULT_BASH_EXECUTABLE).orElseThrow();
  }

  private static DaemonConfig load(Path file) {
    return DaemonConfig.fromArgs(new String[] {"--config", file.toString()});
  }
}
