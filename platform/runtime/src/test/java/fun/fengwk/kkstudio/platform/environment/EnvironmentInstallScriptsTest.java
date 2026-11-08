package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigCodec;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 生成命令必须携带卡片本地信封与固定 tag 安装器，保留宿主代理环境，且不把凭据放进子进程参数。 */
class EnvironmentInstallScriptsTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String RELEASE = "3.0.0";
  private static final String TOKEN = "private-汉字'\"$`$(touch NEVER)";

  @ParameterizedTest
  @ValueSource(strings = {"linux", "macos"})
  void unixInstallStagesEnvelopeAndInvokesCardScopedInstaller(String operatingSystem)
      throws Exception {
    assumeFalse(isWindows());
    Execution execution =
        execute(
            EnvironmentInstallScripts.install(
                ENV_ID,
                12L,
                daemonConfig(),
                TOKEN,
                DaemonOperatingSystem.fromWireValue(operatingSystem),
                RELEASE),
            0);
    assertEquals(0, execution.status());
    assertFalse((execution.stdout() + execution.stderr()).contains("private-"));
    String stage = execution.record().get("stage").asText();
    assertEquals(
        List.of(
            "install",
            "--environment-id",
            ENV_ID.toString(),
            "--version",
            RELEASE,
            "--config-file",
            stage + "/daemon.json",
            "--token-file",
            stage + "/daemon.token"),
        texts(execution.record().get("args")));
    assertEquals(TOKEN, execution.record().get("token").asText());
    assertEquals(expectedEnvelope(), execution.record().get("config"));
    assertEquals(List.of(448, 384, 384), modes(execution.record()));
    assertFalse(
        texts(execution.record().get("args")).stream().anyMatch(value -> value.contains(TOKEN)));
    assertFalse(
        execution.record().get("env").properties().stream()
            .anyMatch(entry -> entry.getValue().asText().contains(TOKEN)));
    // 人工下载保留调用方本机代理环境，生成脚本不得清理或改写。
    assertEquals(
        "http://host-proxy.invalid:3128",
        execution.record().get("env").get("HTTPS_PROXY").asText());
    assertEquals(
        "localhost,.internal",
        execution.record().get("env").get("NO_PROXY").asText());
    assertEquals(
        List.of("-q", "-fsSL", installerUrl("install.sh"), "-o", stage + "/install.sh"),
        texts(execution.download().get("args")));
    assertFalse(Files.exists(Path.of(stage)));
  }

  @Test
  void unixInstallCleansAfterInstallerFailure() throws Exception {
    assumeFalse(isWindows());
    Execution execution =
        execute(
            EnvironmentInstallScripts.install(
                ENV_ID,
                12L,
                daemonConfig(),
                "private",
                DaemonOperatingSystem.LINUX,
                RELEASE),
            17);
    assertEquals(17, execution.status());
    assertFalse(Files.exists(Path.of(execution.record().get("stage").asText())));
  }

  @Test
  void unixDownloadFailureCleansStagingWithoutLeakingToken() throws Exception {
    assumeFalse(isWindows());
    Path dir = Files.createTempDirectory("kk-download-failure-");
    try {
      writeExecutable(dir.resolve("curl"), "#!/bin/bash\nexit 8\n");
      ProcessResult result =
          run(
              EnvironmentInstallScripts.install(
                  ENV_ID,
                  12L,
                  daemonConfig(),
                  TOKEN,
                  DaemonOperatingSystem.LINUX,
                  RELEASE),
              dir);
      assertEquals(8, result.status());
      assertEquals(List.of("curl"), names(dir));
      assertFalse((result.stdout() + result.stderr()).contains(TOKEN));
    } finally {
      delete(dir);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"linux", "macos"})
  void unixUninstallOnlyTargetsTheCard(String operatingSystem) throws Exception {
    assumeFalse(isWindows());
    Execution execution =
        execute(
            EnvironmentInstallScripts.uninstall(
                ENV_ID, DaemonOperatingSystem.fromWireValue(operatingSystem), RELEASE),
            0);
    assertEquals(0, execution.status());
    assertEquals(
        List.of("uninstall", "--environment-id", ENV_ID.toString()),
        texts(execution.record().get("args")));
    assertFalse(execution.record().has("token"));
    assertFalse(execution.record().has("config"));
  }

  @Test
  void windowsInstallProtectsAclBeforeWritingCredentialsAndKeepsHostProxy() {
    String command =
        EnvironmentInstallScripts.install(
            ENV_ID, 12L, daemonConfig(), TOKEN, DaemonOperatingSystem.WINDOWS, RELEASE);
    assertTrue(command.contains("function Set-KkPrivateAcl"));
    assertTrue(command.contains("[Security.AccessControl.DirectorySecurity]::new()"));
    assertTrue(command.contains("[Security.AccessControl.FileSecurity]::new()"));
    assertTrue(command.contains("$acl.SetAccessRuleProtection($true, $false)"));
    assertTrue(command.contains("New-Item -ItemType Directory -Path $stage"));
    assertTrue(command.contains("[IO.FileMode]::CreateNew"));
    assertTrue(command.contains("New-Object Text.UTF8Encoding($false)"));
    assertTrue(
        command.indexOf("Set-KkPrivateAcl -Path $stage -Directory")
            < command.indexOf("WriteAllText($config"));
    assertTrue(
        command.indexOf("Set-KkPrivateAcl -Path $path") < command.indexOf("WriteAllText($config"));
    assertTrue(
        command.indexOf("Set-KkPrivateAcl -Path $path") < command.indexOf("WriteAllText($token"));
    assertTrue(command.contains("Set-PSDebug -Off"));
    assertTrue(
        command.contains(
            "throw 'Daemon command failed; review installer output and host prerequisites.'"));
    assertTrue(command.contains("finally"));
    assertTrue(command.contains("Remove-Item -LiteralPath $stage -Recurse -Force"));
    assertTrue(command.contains("'private-''"));
    assertTrue(command.contains("汉字"));
    assertFalse(command.contains("$env:HTTP_PROXY ="));
    assertFalse(command.contains("$env:NO_PROXY ="));
    String invocation =
        command.lines().filter(line -> line.contains("& powershell")).findFirst().orElseThrow();
    assertTrue(
        invocation.contains(
            "-File $installer -Command install -EnvironmentId '"
                + ENV_ID
                + "' -Version '"
                + RELEASE
                + "' -ConfigFile $config -TokenFile $token"));
    assertFalse(invocation.contains("-JavaHome"));
    assertFalse(invocation.contains("private"));

    String uninstall =
        EnvironmentInstallScripts.uninstall(ENV_ID, DaemonOperatingSystem.WINDOWS, RELEASE);
    assertFalse(uninstall.contains("WriteAllText"));
    assertTrue(uninstall.contains("Set-KkPrivateAcl -Path $stage -Directory"));
    assertTrue(
        uninstall.contains(
            "-File $installer -Command uninstall -EnvironmentId '" + ENV_ID + "'"));
    assertFalse(uninstall.contains("daemon.token"));
  }

  @Test
  void rejectsBlankTokenControlCharactersAndInvalidInputs() {
    assertThrows(
        DomainException.class,
        () ->
            EnvironmentInstallScripts.install(
                ENV_ID, 0L, daemonConfig(), " \n", DaemonOperatingSystem.LINUX, RELEASE));
    assertThrows(
        DomainException.class,
        () ->
            EnvironmentInstallScripts.install(
                ENV_ID, 0L, daemonConfig(), "token\u2028", DaemonOperatingSystem.LINUX, RELEASE));
    assertThrows(
        DomainException.class,
        () ->
            EnvironmentInstallScripts.install(
                ENV_ID, 0L, daemonConfig(), "token", DaemonOperatingSystem.WSL, RELEASE));
    assertThrows(
        NullPointerException.class,
        () -> EnvironmentInstallScripts.install(null, 0L, daemonConfig(), "token", null, RELEASE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentInstallScripts.install(
                ENV_ID, -1L, daemonConfig(), "token", DaemonOperatingSystem.LINUX, RELEASE));
    assertThrows(
        IllegalStateException.class,
        () ->
            EnvironmentInstallScripts.install(
                ENV_ID, 0L, daemonConfig(), "token", DaemonOperatingSystem.LINUX, ""));
    assertThrows(
        DomainException.class,
        () -> EnvironmentInstallScripts.uninstall(ENV_ID, DaemonOperatingSystem.WSL, RELEASE));
    assertThrows(
        DomainException.class, () -> EnvironmentInstallScripts.uninstall(ENV_ID, null, RELEASE));
  }

  private static Execution execute(String command, int exit) throws Exception {
    Path dir = Files.createTempDirectory("kk-command-");
    Path result = dir.resolve("record.json");
    Path download = dir.resolve("download.json");
    Path installer = dir.resolve("installer");
    writeExecutable(
        installer,
        """
        #!/bin/bash
        python3 - "$@" <<'PY'
        import json, os, stat, sys
        args = sys.argv[1:]
        data = {'args': args, 'env': dict(os.environ)}
        if 'install' in args:
            c = args[args.index('--config-file') + 1]
            t = args[args.index('--token-file') + 1]
            data.update(config=json.load(open(c)), token=open(t).read(), stage=os.path.dirname(c),
                        modes=[stat.S_IMODE(os.stat(p).st_mode) for p in [os.path.dirname(c), c, t]])
        json.dump(data, open(os.environ['RECORD'], 'w'))
        PY
        exit %d
        """
            .formatted(exit));
    writeExecutable(
        dir.resolve("curl"),
        """
        #!/bin/bash
        python3 - "$@" <<'PY'
        import json, os, sys
        json.dump({'args': sys.argv[1:]}, open(os.environ['DOWNLOAD'], 'w'))
        PY
        while [[ "$1" != "-o" ]]; do shift; done
        cp "$FIXTURE" "$2"
        """);
    try {
      ProcessResult run =
          run(
              command,
              dir,
              Map.of(
                  "FIXTURE", installer.toString(),
                  "RECORD", result.toString(),
                  "DOWNLOAD", download.toString(),
                  "HTTPS_PROXY", "http://host-proxy.invalid:3128",
                  "NO_PROXY", "localhost,.internal",
                  "SHELLOPTS", "xtrace:verbose",
                  "BASH_FUNC_printf%%", "() { echo unsafe >&2; exit 42; }"));
      return new Execution(
          run,
          JSON.readTree(Files.readString(result)),
          JSON.readTree(Files.readString(download)));
    } finally {
      delete(dir);
    }
  }

  private static ProcessResult run(String command, Path dir) throws Exception {
    return run(command, dir, Map.of());
  }

  private static ProcessResult run(String command, Path dir, Map<String, String> extra)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder("bash");
    builder.directory(dir.toFile());
    builder.environment().put("PATH", dir + ":" + System.getenv("PATH"));
    builder.environment().put("TMPDIR", ".");
    builder.environment().putAll(extra);
    Process process = builder.start();
    process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.waitFor(), stdout, stderr);
  }

  private static DaemonConfigDTO daemonConfig() {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl("https://studio.example.com/");
    config.setBashExecutable("/bin/汉字 '$`");
    return config;
  }

  private static JsonNode expectedEnvelope() {
    var envelope = JSON.createObjectNode();
    envelope.put("environmentId", ENV_ID.toString());
    envelope.put("version", "12");
    envelope.set("daemonConfig", DaemonConfigCodec.tree(daemonConfig()));
    return envelope;
  }

  private static String installerUrl(String file) {
    return "https://raw.githubusercontent.com/fengwk/kk-studio/v3.0.0/scripts/daemon/" + file;
  }

  private static List<Integer> modes(JsonNode record) {
    return record.get("modes").valueStream().map(JsonNode::intValue).toList();
  }

  private static List<String> texts(JsonNode node) {
    return node.valueStream().map(JsonNode::asText).toList();
  }

  private static List<String> names(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      return files.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(
        path,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  private static void delete(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  private record Execution(ProcessResult run, JsonNode record, JsonNode download) {
    private int status() {
      return run.status();
    }

    private String stdout() {
      return run.stdout();
    }

    private String stderr() {
      return run.stderr();
    }
  }

  private record ProcessResult(int status, String stdout, String stderr) {}
}
