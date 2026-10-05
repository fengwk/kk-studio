package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
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
    assertFalse((execution.stdout() + execution.stderr()).contains("private-"));
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
    assertFalse(
        texts(execution.record().get("args")).stream().anyMatch(value -> value.contains(token)));
    assertFalse(
        execution.record().get("env").properties().stream()
            .anyMatch(entry -> entry.getValue().asText().contains(token)));
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

  /** 只在 Windows 执行生成脚本。下载由同进程函数覆盖，不改生成文本、不访问网络； Linux 上的 PowerShell 不能代替 Windows SID ACL。 */
  @ParameterizedTest
  @ValueSource(strings = {"powershell", "pwsh"})
  @EnabledOnOs(OS.WINDOWS)
  void executesWindowsInstallAndUninstall(String shell) throws Exception {
    Path executable = Path.of(shell + ".exe");
    assertTrue(windowsCommandExists(executable), shell + " is not installed");
    Path dir = Files.createTempDirectory("kk-windows-command-");
    Path recordPath = dir.resolve("record.json");
    Path fixture = dir.resolve("recorder.ps1");
    String token = "private-汉字'\"$`$(New-Item NEVER)";
    EnvironmentInstallConfigDTO config = config("windows", "C:\\Program Files\\Java\\it's");
    Files.writeString(fixture, WINDOWS_RECORDER);
    try {
      ProcessResult failed =
          runWindows(
              executable,
              EnvironmentInstallScripts.install(config, token),
              dir,
              fixture,
              recordPath);
      assertEquals(1, failed.status(), failed.stdout() + failed.stderr());
      assertFalse((failed.stdout() + failed.stderr()).contains("private-"));
      JsonNode record = JSON.readTree(Files.readString(recordPath));
      assertEquals("install", record.get("action").asText());
      assertEquals(token, record.get("token").asText());
      assertEquals(
          Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)),
          record.get("bytes").asText());
      assertEquals(expectedDaemon(config), JSON.readTree(record.get("configRaw").asText()));
      assertEquals(config.getJavaHome(), record.get("javaHome").asText());
      assertTrue(record.get("sibling").asBoolean());
      for (String facts : List.of("dir", "configFile", "tokenFile")) {
        JsonNode acl = record.get(facts);
        assertTrue(acl.get("protected").asBoolean());
        assertEquals(record.get("sid").asText(), acl.get("owner").asText());
        assertEquals(0, acl.get("foreign").asInt());
        assertTrue(acl.get("readable").asInt() >= 1);
      }
      assertFalse(
          record.get("environment").properties().stream()
              .anyMatch(entry -> entry.getValue().asText().contains(token)));
      assertFalse(Files.exists(Path.of(record.get("stage").asText())));
      assertFalse(Files.exists(dir.resolve("NEVER")));

      ProcessResult removed =
          runWindows(
              executable, EnvironmentInstallScripts.uninstall("windows"), dir, fixture, recordPath);
      assertEquals(1, removed.status(), removed.stdout() + removed.stderr());
      JsonNode uninstall = JSON.readTree(Files.readString(recordPath));
      assertEquals("uninstall", uninstall.get("action").asText());
      assertFalse(uninstall.has("configRaw"));
      assertFalse(uninstall.has("token"));
      assertEquals("", uninstall.get("javaHome").asText());
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
                  "SHELLOPTS", "xtrace:verbose",
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

  private static final String WINDOWS_RECORDER =
      """
      param(
        [Parameter(Position = 0)][string]$Action,
        [string]$ConfigFile,
        [string]$TokenFile,
        [string]$JavaHome
      )
      $ErrorActionPreference = 'Stop'
      $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
      function Get-Facts([string]$Path) {
        $acl = Get-Acl -LiteralPath $Path
        $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        [pscustomobject]@{
          protected = $acl.AreAccessRulesProtected
          owner = (New-Object Security.Principal.NTAccount($acl.Owner)).Translate([Security.Principal.SecurityIdentifier]).Value
          foreign = @($rules | Where-Object { $_.IdentityReference.Value -ne $sid }).Count
          readable = @($rules | Where-Object { $_.IdentityReference.Value -eq $sid -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) -ne 0 }).Count
        }
      }
      $data = @{ action = $Action; javaHome = $JavaHome; environment = @{} }
      Get-ChildItem Env: | ForEach-Object { $data.environment[$_.Name] = $_.Value }
      if ($Action -eq 'install') {
        $data.sid = $sid
        $data.stage = Split-Path $ConfigFile
        $data.sibling = (Split-Path $TokenFile) -eq $data.stage
        $data.configRaw = [IO.File]::ReadAllText($ConfigFile)
        $data.token = [IO.File]::ReadAllText($TokenFile)
        $data.bytes = [Convert]::ToBase64String([IO.File]::ReadAllBytes($TokenFile))
        $data.dir = Get-Facts $data.stage
        $data.configFile = Get-Facts $ConfigFile
        $data.tokenFile = Get-Facts $TokenFile
      }
      [IO.File]::WriteAllText($env:RECORD, ($data | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
      exit 17
      """;

  /** 父脚本带 BOM，使 Windows PowerShell 5.1 按 Unicode 解析；生成脚本文本本身不被改写。 */
  private static ProcessResult runWindows(
      Path shell, String command, Path dir, Path fixture, Path record) throws Exception {
    Path script = dir.resolve("command.ps1");
    String parent =
        """
        function Invoke-WebRequest {
          param([switch]$UseBasicParsing, $Uri, $OutFile)
          if (-not $UseBasicParsing -or [string]::IsNullOrEmpty($Uri) -or [string]::IsNullOrEmpty($OutFile)) {
            throw 'download override did not receive the generated parameters'
          }
          Copy-Item -LiteralPath $env:FIXTURE -Destination $OutFile
        }
        """
            + command;
    byte[] body = parent.getBytes(StandardCharsets.UTF_8);
    byte[] bom = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    byte[] encoded = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, encoded, 0, bom.length);
    System.arraycopy(body, 0, encoded, bom.length, body.length);
    Files.write(script, encoded);
    ProcessBuilder builder =
        new ProcessBuilder(
            shell.toString(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.toString());
    builder.directory(dir.toFile());
    builder.environment().put("RECORD", record.toString());
    builder.environment().put("FIXTURE", fixture.toString());
    Process process = builder.start();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.waitFor(), stdout, stderr);
  }

  private static boolean windowsCommandExists(Path executable) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String entry : path.split(";")) {
      if (Files.isExecutable(Path.of(entry).resolve(executable))) {
        return true;
      }
    }
    return false;
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
