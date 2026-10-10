package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.OfficialReleaseHttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 受管安装的二进制更新准备器：下载官方制品、校验 SHA256 与 manifest 版本、用磁盘配置做预检，然后把制品与更新脚本交给独立更新器。
 *
 * <p><b>仅受管安装：</b>只有当数据目录呈受管布局（存在 {@code lib/kk-studio-daemon.jar} 与同目录 {@code daemon.json}）时才接受更新；
 * 其它布局一律拒绝，旧二进制不动。受管根、{@code lib}、二进制、配置、{@code updates} 与 operation 目录都拒绝符号链接/reparse 点，
 * 防止更新被重定向到数据目录之外。
 *
 * <p><b>可信固定来源：</b>目标只来自 {@link DaemonUpdateCommand} 的官方 Release URL；这里再次校验 URL 形状、SHA256、官方校验文件以及
 * JAR manifest 的 {@code Implementation-Version}，绝不接受任意 URL 或 "latest"。
 *
 * <p><b>不改磁盘配置：</b>预检用现有 {@code daemon.json} 与 {@code daemon.token}，不重写它们；更新只替换 {@code lib} 下的二进制。
 *
 * <p><b>幂等准备：</b>同一 {@code operationId} 的重复命令复用已完成的暂存制品（handoff 已存在、SHA256 一致），绝不第二次下载。
 *
 * <p><b>handoff 边界：</b>{@link #prepare(DaemonUpdateCommand)} 只把验证过的制品、更新脚本与元数据写到 {@code
 * <dataDir>/updates/<operationId>/}（进程私有目录）并返回 handoff；真正替换与重启旧服务由独立于旧 service/task 生命周期的 OS
 * 更新器阶段完成。
 */
public final class ManagedDaemonUpdater implements ManagedUpdatePreparer {

  /** 受管二进制文件名（由安装器布局固定）。 */
  public static final String MANAGED_JAR_NAME = "kk-studio-daemon.jar";

  /** 受管配置文件相对数据目录的固定名。 */
  public static final String MANAGED_CONFIG_NAME = "daemon.json";

  /** 暂存与 handoff 的进程私有子目录。 */
  public static final String UPDATE_DIRECTORY = "updates";

  /** handoff 元数据文件名；下一阶段的 OS 更新器按它执行替换与重启。 */
  public static final String HANDOFF_FILE = "handoff.json";

  /** 供独立更新器读取的最小证据文件名（阶段与去敏说明）。 */
  public static final String RESULT_FILE = "update-result.json";

  /** Unix 更新脚本在暂存目录内的固定文件名。 */
  public static final String UNIX_UPDATE_SCRIPT = "kk-studio-daemon-update.sh";

  /** Windows 更新脚本在暂存目录内的固定文件名。 */
  public static final String WINDOWS_UPDATE_SCRIPT = "kk-studio-daemon-update.ps1";

  private static final String UNIX_UPDATE_SCRIPT_RESOURCE =
      "/fun/fengwk/kkstudio/harness/daemon/update/update.sh";
  private static final String WINDOWS_UPDATE_SCRIPT_RESOURCE =
      "/fun/fengwk/kkstudio/harness/daemon/update/update.ps1";

  private static final String MANIFEST_VERSION_ATTRIBUTE = "Implementation-Version";
  private static final String CHECK_CONFIG_SUCCESS = "Daemon configuration is valid";

  /** {@code --check-config} 预检的子进程超时。 */
  private static final int CHECK_CONFIG_TIMEOUT_SECONDS = 60;

  /** 预检输出最多保留的字符数。 */
  private static final int PREFLIGHT_OUTPUT_LIMIT = 4096;

  /** 官方制品下载器；生产用受限官方 HTTP 传输，测试可注入本地实现。 */
  @FunctionalInterface
  public interface ArtifactFetcher {
    void fetch(URI source, Path target) throws IOException;
  }

  /** 预检执行器：用暂存 JAR 与磁盘配置运行 {@code --check-config}，返回是否通过。 */
  @FunctionalInterface
  public interface ConfigPreflight {
    boolean verify(Path stagedJar, Path configFile);
  }

  private final Path dataDir;
  private final ArtifactFetcher fetcher;
  private final ConfigPreflight preflight;
  private final ObjectMapper mapper = new ObjectMapper();

  public ManagedDaemonUpdater(Path dataDir) {
    this(dataDir, new HttpArtifactFetcher(), new ProcessConfigPreflight());
  }

  ManagedDaemonUpdater(Path dataDir, ArtifactFetcher fetcher, ConfigPreflight preflight) {
    this.dataDir = dataDir.toAbsolutePath().normalize();
    this.fetcher = fetcher;
    this.preflight = preflight;
  }

  /** 准备一次受管更新；任何失败都收敛为 {@link ManagedUpdateOutcome.Failed}，旧二进制保持不动。 */
  @Override
  public ManagedUpdateOutcome prepare(DaemonUpdateCommand command) {
    try {
      return prepareChecked(command);
    } catch (IOException error) {
      return new ManagedUpdateOutcome.Failed("update preparation I/O failed");
    } catch (RuntimeException error) {
      return new ManagedUpdateOutcome.Failed("update preparation failed");
    }
  }

  private ManagedUpdateOutcome prepareChecked(DaemonUpdateCommand command) throws IOException {
    if (!isRealDirectory(dataDir)) {
      return new ManagedUpdateOutcome.Failed("managed installation root is not a real directory");
    }
    Path lib = dataDir.resolve("lib");
    Path managedJar = lib.resolve(MANAGED_JAR_NAME);
    Path configFile = dataDir.resolve(MANAGED_CONFIG_NAME);
    if (!isRealDirectory(lib) || !isRealRegularFile(managedJar) || !isRealRegularFile(configFile)) {
      return new ManagedUpdateOutcome.Failed("managed installation layout is not present");
    }
    String targetVersion = command.targetVersion();
    // 目标 URL 必须与该版本对应的官方发布形态一致，防止伪造命令把 Daemon 指向别处。
    if (!DaemonUpdateArtifact.artifactUrl(targetVersion).equals(command.artifactUrl())) {
      return new ManagedUpdateOutcome.Failed(
          "update artifact URL is not the official release target");
    }
    Path updates = dataDir.resolve(UPDATE_DIRECTORY);
    if (Files.exists(updates, LinkOption.NOFOLLOW_LINKS) && !isRealDirectory(updates)) {
      return new ManagedUpdateOutcome.Failed("update directory is not a real directory");
    }
    Path updateRoot = updates.resolve(command.operationId());
    if (!updateRoot.normalize().startsWith(dataDir)) {
      return new ManagedUpdateOutcome.Failed("update target escapes the data directory");
    }
    if (Files.exists(updateRoot, LinkOption.NOFOLLOW_LINKS) && !isRealDirectory(updateRoot)) {
      return new ManagedUpdateOutcome.Failed("update target is not a real directory");
    }
    protectDirectory(updateRoot);
    Path stagedJar = updateRoot.resolve(DaemonUpdateArtifact.jarName(targetVersion));
    Path stagedChecksum =
        updateRoot.resolve(DaemonUpdateArtifact.jarName(targetVersion) + ".sha256");
    Path updateScript = updateRoot.resolve(updateScriptFileName());

    // 幂等：同一 operation 的重复命令复用已验证的暂存制品，绝不产生第二次下载。
    if (isReusableHandoff(updateRoot, stagedJar, targetVersion, command.artifactSha256())) {
      extractUpdateScript(updateScript);
      return new ManagedUpdateOutcome.Prepared(
          targetVersion, updateRoot, updateScript, stagedJar, managedJar);
    }

    fetcher.fetch(URI.create(command.artifactUrl()), stagedJar);
    fetcher.fetch(URI.create(DaemonUpdateArtifact.checksumUrl(targetVersion)), stagedChecksum);
    if (!isRealRegularFile(stagedJar) || !isRealRegularFile(stagedChecksum)) {
      return new ManagedUpdateOutcome.Failed("downloaded artifact is not a real file");
    }
    String actualSha = sha256Hex(stagedJar);
    if (!actualSha.equalsIgnoreCase(command.artifactSha256())) {
      return new ManagedUpdateOutcome.Failed(
          "downloaded artifact SHA256 does not match the command");
    }
    if (!readChecksumDigest(stagedChecksum).equals(actualSha)) {
      return new ManagedUpdateOutcome.Failed("official checksum file does not match the artifact");
    }
    String manifestVersion = readManifestVersion(stagedJar);
    if (!targetVersion.equals(manifestVersion)) {
      return new ManagedUpdateOutcome.Failed(
          "staged artifact manifest version does not match target");
    }
    if (!preflight.verify(stagedJar, configFile)) {
      return new ManagedUpdateOutcome.Failed(
          "staged artifact rejected the existing daemon configuration");
    }
    extractUpdateScript(updateScript);
    writeHandoff(updateRoot, command, updateScript, stagedJar, managedJar, configFile);
    return new ManagedUpdateOutcome.Prepared(
        targetVersion, updateRoot, updateScript, stagedJar, managedJar);
  }

  /**
   * 同一 operation 的 handoff 是否仍可复用：handoff 存在、目标版本与命令 SHA256 一致，且暂存 JAR 仍在且摘要一致。
   *
   * <p>只信任本机写入的 handoff 与已校验制品；任何读取或摘要失败都返回 false，退化为重新下载而不是复用可疑内容。
   */
  private boolean isReusableHandoff(
      Path updateRoot, Path stagedJar, String targetVersion, String expectedSha256) {
    Path handoff = updateRoot.resolve(HANDOFF_FILE);
    if (!isRealRegularFile(handoff) || !isRealRegularFile(stagedJar)) {
      return false;
    }
    try {
      JsonNode root = mapper.readTree(Files.readString(handoff, StandardCharsets.UTF_8));
      if (!targetVersion.equals(text(root, "targetVersion"))
          || !expectedSha256.equalsIgnoreCase(text(root, "artifactSha256"))) {
        return false;
      }
      return expectedSha256.equalsIgnoreCase(sha256Hex(stagedJar));
    } catch (IOException | RuntimeException error) {
      return false;
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root == null ? null : root.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private void extractUpdateScript(Path updateScript) throws IOException {
    String resource =
        updateScriptFileName().equals(WINDOWS_UPDATE_SCRIPT)
            ? WINDOWS_UPDATE_SCRIPT_RESOURCE
            : UNIX_UPDATE_SCRIPT_RESOURCE;
    try (InputStream source = ManagedDaemonUpdater.class.getResourceAsStream(resource)) {
      if (source == null) {
        throw new IOException("update script resource is missing: " + resource);
      }
      Files.copy(source, updateScript, StandardCopyOption.REPLACE_EXISTING);
    }
    updateScript.toFile().setReadable(true, true);
    updateScript.toFile().setWritable(true, true);
    updateScript.toFile().setExecutable(true, true);
  }

  private String updateScriptFileName() {
    return DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS
        ? WINDOWS_UPDATE_SCRIPT
        : UNIX_UPDATE_SCRIPT;
  }

  private void writeHandoff(
      Path updateRoot,
      DaemonUpdateCommand command,
      Path updateScript,
      Path stagedJar,
      Path managedJar,
      Path configFile)
      throws IOException {
    ObjectNode handoff = mapper.createObjectNode();
    handoff.put("operationId", command.operationId());
    handoff.put("targetVersion", command.targetVersion());
    handoff.put("artifactSha256", command.artifactSha256());
    handoff.put("updateScript", updateScript.toString());
    handoff.put("stagedJar", stagedJar.toString());
    handoff.put("installedJar", managedJar.toString());
    handoff.put("configFile", configFile.toString());
    writeAtomic(updateRoot.resolve(HANDOFF_FILE), mapper.writeValueAsString(handoff));
  }

  private static void writeAtomic(Path target, String content) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".part");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** 私有暂存目录：拒绝符号链接并用 POSIX 0700 收紧（非 POSIX 平台忽略）。 */
  private static void protectDirectory(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (!isRealDirectory(directory)) {
      throw new IOException("update directory is not a real directory");
    }
    try {
      Files.setPosixFilePermissions(
          directory,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException ignored) {
      // 非 POSIX 文件系统：依赖数据目录自身的权限边界。
    }
  }

  /** 真实目录：不跟随符号链接。 */
  private static boolean isRealDirectory(Path path) {
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  /** 真实普通文件：不跟随符号链接。 */
  private static boolean isRealRegularFile(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  private static String readChecksumDigest(Path checksumFile) throws IOException {
    String content = Files.readString(checksumFile, StandardCharsets.UTF_8).strip();
    if (content.isEmpty()) {
      throw new IOException("checksum file is empty");
    }
    String digest = content.split("\\s+", 2)[0];
    if (!DaemonUpdateArtifact.SHA256_PATTERN.matcher(digest).matches()) {
      throw new IOException("checksum file does not contain a SHA256 digest");
    }
    return digest.toLowerCase();
  }

  private static String sha256Hex(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String readManifestVersion(Path jar) throws IOException {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      Manifest manifest = jarFile.getManifest();
      if (manifest == null) {
        throw new IOException("staged artifact has no manifest");
      }
      String version = manifest.getMainAttributes().getValue(MANIFEST_VERSION_ATTRIBUTE);
      if (version == null || version.isBlank()) {
        throw new IOException("staged artifact manifest has no version");
      }
      return version;
    }
  }

  /** 生产下载器：受限官方 HTTPS 传输（约束重定向、有界正文、无 disposition 依赖）。 */
  static final class HttpArtifactFetcher implements ArtifactFetcher {

    private final OfficialReleaseHttp http = new OfficialReleaseHttp(Duration.ofSeconds(30));

    @Override
    public void fetch(URI source, Path target) throws IOException {
      if (!"https".equals(source.getScheme())) {
        throw new IOException("update artifact must be https");
      }
      http.download(source.toString(), target);
    }
  }

  /**
   * 生产预检：以当前 JDK 运行暂存 JAR 的 {@code --check-config}，要求退出码为 0 且打印固定成功文本。
   *
   * <p>输出重定向到文件后再有界读取，避免先读管道再等待导致子进程写满管道时永久阻塞；超时或被中断时强制结束并回收进程。
   */
  static final class ProcessConfigPreflight implements ConfigPreflight {

    @Override
    public boolean verify(Path stagedJar, Path configFile) {
      Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
      Path log;
      try {
        log = Files.createTempFile(stagedJar.getParent(), "preflight-", ".log");
      } catch (IOException error) {
        return false;
      }
      ProcessBuilder builder =
          new ProcessBuilder(
              javaExecutable.toString(),
              "-jar",
              stagedJar.toString(),
              "--check-config",
              configFile.toString());
      builder.redirectErrorStream(true);
      builder.redirectOutput(log.toFile());
      Process process = null;
      try {
        process = builder.start();
        boolean exited = process.waitFor(CHECK_CONFIG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          return false;
        }
        String output = readBounded(log, PREFLIGHT_OUTPUT_LIMIT);
        return process.exitValue() == 0 && output.contains(CHECK_CONFIG_SUCCESS);
      } catch (IOException error) {
        return false;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        if (process != null) {
          process.destroyForcibly();
        }
        return false;
      } finally {
        try {
          Files.deleteIfExists(log);
        } catch (IOException ignored) {
          // 临时诊断文件清理失败不影响预检结论。
        }
      }
    }

    private static String readBounded(Path path, int maxChars) throws IOException {
      String content = Files.readString(path, StandardCharsets.UTF_8);
      return content.length() > maxChars ? content.substring(0, maxChars) : content;
    }
  }
}
