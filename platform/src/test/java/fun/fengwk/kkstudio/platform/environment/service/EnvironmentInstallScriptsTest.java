package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生成命令必须保留凭据字节、私有暂存、失败清理和退出码，且不能把凭据放进子进程参数。 */
class EnvironmentInstallScriptsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"linux", "macos"})
  void executesUnixInstallWithExactBytesAndPrivateFiles(String operatingSystem) throws Exception {
    assumeFalse(isWindows());
    EnvironmentInstallConfigDTO config = config(operatingSystem, "/opt/jdk-21/汉字 '`");
    String token = "private-汉字'\"$`$(touch NEVER)";
    Execution execution = execute(EnvironmentInstallScripts.install(config, token), 0);
    assertEquals(0, execution.status());
    assertFalse((execution.stdout() + execution.stderr()).contains(token));
    assertEquals(expectedDaemon(config), execution.record().get("config"));
    assertEquals(token, execution.record().get("token").asText());
    assertTrue(execution.record().get("sibling").asBoolean());
    assertTrue(execution.record().get("stage").asText().startsWith("/"));
    assertEquals(List.of(448, 384, 384), modes(execution.record()));
    assertEquals(
        List.of(
            "install",
            "--config-file",
            execution.record().get("stage").asText() + "/daemon.json",
            "--token-file",
            execution.record().get("stage").asText() + "/daemon.token",
            "--java-home",
            config.getJavaHome()),
        texts(execution.record().get("args")));
    assertFalse(execution.record().toString().contains(token));
    assertFalse(Files.exists(Path.of(execution.record().get("stage").asText())));
  }

  @Test
  void cleansAfterInstallerFailureAndOmitsMissingJavaHome() throws Exception {
    assumeFalse(isWindows());
    EnvironmentInstallConfigDTO config = config("linux", null);
    Execution execution = execute(EnvironmentInstallScripts.install(config, "private"), 17);
    assertEquals(17, execution.status());
    assertFalse(texts(execution.record().get("args")).contains("--java-home"));
    assertFalse(Files.exists(Path.of(execution.record().get("stage").asText())));
  }

  @Test
  void cleansCredentialsWhenDownloadFails() throws Exception {
    assumeFalse(isWindows());
    Path dir = Files.createTempDirectory("kk-download-failure-");
    try {
      writeExecutable(dir.resolve("curl"), "#!/bin/bash\nexit 8\n");
      ProcessResult result =
          run(
              EnvironmentInstallScripts.install(config("linux", "/opt/jdk"), "private"),
              dir,
              Map.of());
      assertEquals(8, result.status());
      assertEquals(List.of("curl"), names(dir));
      assertFalse((result.stdout() + result.stderr()).contains("private"));
    } finally {
      delete(dir);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"linux", "macos"})
  void executesUnixUninstallWithoutCredentials(String operatingSystem) throws Exception {
    assumeFalse(isWindows());
    Execution execution = execute(EnvironmentInstallScripts.uninstall(operatingSystem), 0);
    assertEquals(0, execution.status());
    assertEquals(List.of("uninstall"), texts(execution.record().get("args")));
    assertFalse(execution.record().has("token"));
  }

  @Test
  void windowsCommandProtectsAclBeforeWritingCredentials() {
    EnvironmentInstallConfigDTO config = config("windows", "C:\\Program Files\\Java\\it's");
    String command = EnvironmentInstallScripts.install(config, "private-'$`");
    assertTrue(command.contains("function Set-KkPrivateAcl"));
    assertTrue(command.contains("[Security.AccessControl.DirectorySecurity]::new()"));
    assertTrue(command.contains("[Security.AccessControl.FileSecurity]::new()"));
    assertTrue(command.contains("$acl.SetAccessRuleProtection($true, $false)"));
    assertFalse(command.contains("[IO.Directory]::CreateDirectory"));
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
    assertTrue(command.contains("'private-''$`'"));
    assertTrue(command.contains("汉字"));
    String invocation =
        command.lines().filter(line -> line.contains("& powershell")).findFirst().orElseThrow();
    assertTrue(
        invocation.contains(
            "-File $installer install -ConfigFile $config -TokenFile $token -JavaHome 'C:\\Program Files\\Java\\it''s'"));
    assertFalse(invocation.contains("private"));
    String uninstall = EnvironmentInstallScripts.uninstall("windows");
    assertFalse(uninstall.contains("WriteAllText"));
    assertTrue(uninstall.contains("Set-KkPrivateAcl -Path $stage -Directory"));
    assertTrue(uninstall.contains("-File $installer uninstall"));
    assertFalse(
        EnvironmentInstallScripts.install(config("windows", null), "private")
            .contains("-JavaHome"));
  }

  @Test
  void executesWindowsInstallAndUninstallWithPwsh() throws Exception {
    assumeTrue(
        Files.isExecutable(Path.of("/usr/bin/pwsh")),
        "pwsh is required to execute the Windows command");
    Path dir = Files.createTempDirectory("kk-windows-command-");
    try {
      String install =
          EnvironmentInstallScripts.install(config("windows", "C:\\Java\\jdk"), "private");
      ProcessResult installed = runPwsh(rewriteWindowsDownload(install), dir, true);
      assertEquals(1, installed.status(), installed.stdout() + installed.stderr());
      assertFalse((installed.stdout() + installed.stderr()).contains("private"));
      assertEquals("install", Files.readString(dir.resolve("ran.txt")).strip());
      assertTrue(names(dir).stream().noneMatch(name -> name.length() == 32));

      ProcessResult removed =
          runPwsh(
              rewriteWindowsDownload(EnvironmentInstallScripts.uninstall("windows")), dir, false);
      assertEquals(0, removed.status(), removed.stdout() + removed.stderr());
      assertEquals("uninstall", Files.readString(dir.resolve("ran.txt")).strip());
    } finally {
      delete(dir);
    }
  }

  @Test
  void rejectsBlankTokenControlCharactersAndUnknownOperatingSystem() {
    EnvironmentInstallConfigDTO config = config("linux", null);
    assertThrows(
        AiValidationException.class, () -> EnvironmentInstallScripts.install(config, " \n"));
    assertThrows(
        AiValidationException.class,
        () -> EnvironmentInstallScripts.install(config, "token\u2028"));
    assertThrows(AiValidationException.class, () -> EnvironmentInstallScripts.uninstall("solaris"));
    assertThrows(
        AiValidationException.class, () -> EnvironmentInstallScripts.install(null, "token"));
  }

  private static Execution execute(String command, int exit) throws Exception {
    Path dir = Files.createTempDirectory("kk-command-");
    Path result = dir.resolve("record.json");
    writeExecutable(
        dir.resolve("recorder"),
        """
        #!/bin/bash
        python3 - "$@" <<'PY'
        import json, os, sys, stat
        args = sys.argv[1:]
        data = {'args': args, 'env': dict(os.environ)}
        if args and args[0] == 'install':
            c = args[args.index('--config-file') + 1]
            t = args[args.index('--token-file') + 1]
            data.update(config=json.load(open(c)), token=open(t).read(), stage=os.path.dirname(c),
                        sibling=os.path.dirname(c) == os.path.dirname(t),
                        modes=[stat.S_IMODE(os.stat(p).st_mode) for p in [os.path.dirname(c), c, t]])
        json.dump(data, open(os.environ['RECORD'], 'w'))
        PY
        exit %d
        """
            .formatted(exit));
    writeExecutable(
        dir.resolve("curl"),
        "#!/bin/bash\nwhile [[ \"$1\" != \"-o\" ]]; do shift; done\ncp \"$FIXTURE\" \"$2\"\n");
    try {
      ProcessResult run =
          run(
              command,
              dir,
              Map.of(
                  "TMPDIR", ".",
                  "RECORD", result.toString(),
                  "FIXTURE", dir.resolve("recorder").toString(),
                  "SHELLOPTS", "xtrace",
                  "BASH_FUNC_printf%%", "() { echo unsafe >&2; exit 42; }"));
      return new Execution(run, JSON.readTree(Files.readString(result)));
    } finally {
      delete(dir);
    }
  }

  private static ProcessResult run(String command, Path dir, Map<String, String> extra)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder("bash");
    builder.directory(dir.toFile());
    builder.environment().put("PATH", dir + ":" + System.getenv("PATH"));
    builder.environment().putAll(extra);
    Process process = builder.start();
    process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.waitFor(), stdout, stderr);
  }

  private static String rewriteWindowsDownload(String command) {
    return command.replace(
        "Invoke-WebRequest -UseBasicParsing -Uri",
        "Copy-Item -LiteralPath $env:FIXTURE -Destination");
  }

  private static ProcessResult runPwsh(String command, Path dir, boolean failInstaller)
      throws Exception {
    Path script = dir.resolve("command.ps1");
    Files.writeString(script, command);
    Path fixture = dir.resolve("install.ps1");
    Files.writeString(
        fixture,
        """
        param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
        Set-Content -LiteralPath (Join-Path $env:TMPDIR 'ran.txt') -Value $Arguments[0] -Encoding ascii
        if ($env:FAIL_INSTALLER -eq '1') { exit 9 }
        exit 0
        """);
    ProcessBuilder builder = new ProcessBuilder("pwsh", "-NoProfile", "-File", script.toString());
    builder.directory(dir.toFile());
    builder.environment().put("TMPDIR", dir.toString());
    builder.environment().put("FIXTURE", fixture.toString());
    if (failInstaller) {
      builder.environment().put("FAIL_INSTALLER", "1");
    }
    Process process = builder.start();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.waitFor(), stdout, stderr);
  }

  private static EnvironmentInstallConfigDTO config(String operatingSystem, String javaHome) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operatingSystem", operatingSystem);
    body.put("javaHome", javaHome);
    body.put(
        "daemon",
        Map.of(
            "studioUrl", "https://studio.example.com/",
            "note",
                "汉字 ' \" $ ` $(touch NEVER) %s @@ACTION@@ @@PARAMETERS@@ @@INSTALLER@@ @@STAGING@@",
            "bashExecutable", "/bin/汉字 '$`"));
    return EnvironmentInstallConfigs.parse(JSON.valueToTree(body));
  }

  private static JsonNode expectedDaemon(EnvironmentInstallConfigDTO config) throws Exception {
    return JSON.readTree(JSON.writeValueAsString(config.getDaemon()));
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

  private record Execution(ProcessResult run, JsonNode record) {
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
