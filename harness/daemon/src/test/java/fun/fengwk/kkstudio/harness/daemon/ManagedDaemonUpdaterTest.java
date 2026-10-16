package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * 意图：受管更新准备只在真实受管布局上工作，校验官方 URL/SHA256/manifest/预检，失败保留旧二进制，且 handoff 只有 ACCEPTED 可复用，绝不对
 * PREPARING/FAILED/不匹配重新下载，也拒绝符号链接逃逸。
 */
class ManagedDaemonUpdaterTest {

  private static final String OP = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_OP = "22222222-2222-2222-2222-222222222222";

  @TempDir Path root;

  private Path dataDir;
  private Path updateRoot;
  private Path installedJar;
  private FakeFetcher fetcher;
  private byte[] artifact;
  private String artifactSha;

  @BeforeEach
  void setUp() throws IOException {
    dataDir = root.resolve("data");
    Path lib = dataDir.resolve("lib");
    Files.createDirectories(lib);
    updateRoot = dataDir.resolve(ManagedDaemonUpdater.UPDATE_DIRECTORY).resolve(OP);
    installedJar = lib.resolve(ManagedDaemonUpdater.MANAGED_JAR_NAME);
    Files.write(installedJar, new byte[] {1, 2, 3});
    Files.writeString(dataDir.resolve(ManagedDaemonUpdater.MANAGED_CONFIG_NAME), "{}");
    artifact = jarWithVersion("1.0.9");
    artifactSha = sha256Hex(artifact);
    fetcher = new FakeFetcher(artifact, artifactSha);
  }

  private ManagedDaemonUpdater updater(boolean preflight) {
    return new ManagedDaemonUpdater(dataDir, fetcher, (jar, config) -> preflight);
  }

  private static DaemonUpdateCommand command(
      String operationId, String version, String url, String sha) {
    return new DaemonUpdateCommand(operationId, version, url, sha);
  }

  private static DaemonUpdateCommand officialCommand(
      String operationId, String version, String sha) {
    return command(operationId, version, DaemonUpdateArtifact.artifactUrl(version), sha);
  }

  @Test
  void preparesHandoffAsPreparingAndLauncherInputs() {
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));

    ManagedUpdateOutcome.Prepared prepared =
        assertInstanceOf(ManagedUpdateOutcome.Prepared.class, outcome);
    assertEquals("1.0.9", prepared.targetVersion());
    assertEquals(installedJar, prepared.installedJar());
    assertEquals(updateRoot, prepared.handoffDirectory());
    assertEquals(UpdateHandoff.State.PREPARING, state(officialCommand(OP, "1.0.9", artifactSha)));
    assertTrue(Files.isRegularFile(prepared.stagedJar()));
    assertTrue(Files.isRegularFile(prepared.updateScript()));
    assertTrue(Files.isExecutable(prepared.updateScript()));
    // 许可只在回执入缓存并完成一次出站尝试后由编排层释放，准备阶段绝不释放。
    assertFalse(Files.exists(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)));
  }

  /** 只有 OS 已接受的 handoff 可复用：不第二次下载，也不再启动。 */
  @Test
  void reusesAcceptedHandoffWithoutSecondDownload() throws IOException {
    ManagedDaemonUpdater updater = updater(true);
    ManagedUpdateOutcome.Prepared prepared =
        assertInstanceOf(
            ManagedUpdateOutcome.Prepared.class,
            updater.prepare(officialCommand(OP, "1.0.9", artifactSha)));
    assertEquals(1, fetcher.artifactFetches);

    UpdateHandoff.transition(prepared.handoffDirectory(), UpdateHandoff.State.ACCEPTED);
    assertEquals(UpdateHandoff.State.ACCEPTED, state(officialCommand(OP, "1.0.9", artifactSha)));

    ManagedUpdateOutcome second = updater.prepare(officialCommand(OP, "1.0.9", artifactSha));
    ManagedUpdateOutcome.AlreadyAccepted accepted =
        assertInstanceOf(ManagedUpdateOutcome.AlreadyAccepted.class, second);
    // 重放事实必须携带既有交接路径，编排层才能幂等释放 matching permit。
    assertEquals(updateRoot, accepted.prepared().handoffDirectory());
    assertEquals(prepared.stagedJar(), accepted.prepared().stagedJar());
    assertEquals(1, fetcher.artifactFetches, "an accepted operation must not download again");
  }

  /** PREPARING 表示已提交但未确认接受：必须 fail closed，绝不第二次下载。 */
  @Test
  void rejectsPreparingHandoffWithoutSecondDownload() {
    ManagedDaemonUpdater updater = updater(true);
    updater.prepare(officialCommand(OP, "1.0.9", artifactSha));

    ManagedUpdateOutcome second = updater.prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, second);
    assertEquals(1, fetcher.artifactFetches, "a preparing operation must not download again");
  }

  /** FAILED 不可复用，也不静默重新下载。 */
  @Test
  void rejectsFailedHandoffWithoutSecondDownload() throws IOException {
    ManagedDaemonUpdater updater = updater(true);
    updater.prepare(officialCommand(OP, "1.0.9", artifactSha));
    UpdateHandoff.transition(updateRoot, UpdateHandoff.State.FAILED);
    assertEquals(UpdateHandoff.State.FAILED, state(officialCommand(OP, "1.0.9", artifactSha)));

    ManagedUpdateOutcome second = updater.prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, second);
    assertEquals(1, fetcher.artifactFetches, "a failed operation must not download again");
  }

  /** operation 目录里已有另一条更新的元数据：拒绝，绝不下载。 */
  @Test
  void rejectsMismatchedHandoffMetadataWithoutDownload() throws IOException {
    Files.createDirectories(updateRoot);
    UpdateHandoff.writePreparing(
        updateRoot,
        officialCommand(OTHER_OP, "1.0.9", artifactSha),
        updateRoot.resolve("other.sh"),
        updateRoot.resolve("other.jar"),
        installedJar,
        dataDir.resolve(ManagedDaemonUpdater.MANAGED_CONFIG_NAME),
        null);
    assertEquals(UpdateHandoff.State.UNUSABLE, state(officialCommand(OP, "1.0.9", artifactSha)));

    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertEquals(0, fetcher.artifactFetches, "mismatched metadata must not trigger a download");
  }

  /** 释放许可写入的正是本 operationId；独立更新器据此放行。 */
  @Test
  void releasePermitWritesTheOperationId() throws IOException {
    ManagedDaemonUpdater updater = updater(true);
    ManagedUpdateOutcome.Prepared prepared =
        (ManagedUpdateOutcome.Prepared) updater.prepare(officialCommand(OP, "1.0.9", artifactSha));

    UpdateHandoff.releasePermit(prepared.handoffDirectory(), OP);

    assertEquals(OP, Files.readString(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)).strip());
  }

  @Test
  void failsWhenDownloadedShaDoesNotMatchCommand() {
    ManagedUpdateOutcome outcome =
        updater(true).prepare(officialCommand(OP, "1.0.9", "b".repeat(64)));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertNoHandoff();
  }

  @Test
  void failsWhenOfficialChecksumDoesNotMatch() {
    fetcher.checksum = "c".repeat(64) + "  artifact.jar\n";
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertNoHandoff();
  }

  @Test
  void failsWhenManifestVersionDoesNotMatch() throws IOException {
    byte[] other = jarWithVersion("1.0.8");
    fetcher.artifact = other;
    fetcher.checksum = sha256Hex(other) + "  artifact.jar\n";
    ManagedUpdateOutcome outcome =
        updater(true).prepare(officialCommand(OP, "1.0.9", sha256Hex(other)));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenPreflightRejectsConfiguration() {
    ManagedUpdateOutcome outcome =
        updater(false).prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenArtifactUrlDoesNotMatchTargetVersion() {
    ManagedUpdateOutcome outcome =
        updater(true)
            .prepare(command(OP, "1.0.9", DaemonUpdateArtifact.artifactUrl("1.0.8"), artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenManagedLayoutIsMissing() throws IOException {
    Files.delete(installedJar);
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void refusesSymlinkedManagedArtifact() throws IOException {
    assumeTrue(Files.getFileStore(dataDir).supportsFileAttributeView("posix"));
    Path elsewhere = root.resolve("elsewhere.jar");
    Files.write(elsewhere, new byte[] {9});
    Files.delete(installedJar);
    Files.createSymbolicLink(installedJar, elsewhere);

    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertFalse(Files.exists(updateRoot));
  }

  /** 预置同名 {@code .part} 符号链接：临时文件必须独占随机名，绝不跟随链接写到外部目标。 */
  @Test
  void doesNotFollowPreplacedPartSymlink() throws IOException {
    assumeTrue(Files.getFileStore(dataDir).supportsFileAttributeView("posix"));
    Path elsewhere = root.resolve("part-target.txt");
    Files.writeString(elsewhere, "sentinel");
    Files.createDirectories(updateRoot);
    Files.createSymbolicLink(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE + ".part"), elsewhere);

    writePreparingHandoff();

    assertEquals(
        "sentinel", Files.readString(elsewhere), "a pre-placed .part link must not be followed");
    assertTrue(
        Files.isRegularFile(
            updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), LinkOption.NOFOLLOW_LINKS));
  }

  /** 预置 handoff.json 符号链接：已存在的目标不是 NOFOLLOW 普通文件，必须直接拒绝且不改动链接条目或其目标。 */
  @Test
  void refusesSymlinkedHandoffTargetWithoutTouchingIt() throws IOException {
    assumeTrue(Files.getFileStore(dataDir).supportsFileAttributeView("posix"));
    Path elsewhere = root.resolve("handoff-target.json");
    Files.writeString(elsewhere, "sentinel");
    Files.createDirectories(updateRoot);
    Files.createSymbolicLink(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), elsewhere);

    assertThrows(IOException.class, this::writePreparingHandoff);

    assertEquals(
        "sentinel", Files.readString(elsewhere), "the link target must not be written through");
    assertTrue(
        Files.isSymbolicLink(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE)),
        "the pre-placed link entry must be left untouched");
  }

  /** 预置目录占位 handoff.json：同样不是普通文件，必须直接拒绝而不替换该条目。 */
  @Test
  void refusesDirectoryHandoffTarget() throws IOException {
    Files.createDirectories(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE));

    assertThrows(IOException.class, this::writePreparingHandoff);

    assertTrue(
        Files.isDirectory(
            updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), LinkOption.NOFOLLOW_LINKS),
        "the pre-placed directory entry must be left untouched");
  }

  /** 已存在的普通 handoff.json 必须被原子覆盖为新内容，且不残留临时文件。 */
  @Test
  void overwritesAnExistingRegularHandoffAtomically() throws IOException {
    Files.createDirectories(updateRoot);
    Files.writeString(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), "sentinel");

    writePreparingHandoff();

    assertEquals(UpdateHandoff.State.PREPARING, state(officialCommand(OP, "1.0.9", artifactSha)));
    assertFalse(
        Files.readString(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE)).contains("sentinel"));
    try (Stream<Path> children = Files.list(updateRoot)) {
      assertEquals(
          0,
          children.filter(path -> path.getFileName().toString().endsWith(".tmp")).count(),
          "no temporary file may be left behind");
    }
  }

  /** 父目录是符号链接：拒绝写入，绝不把交接文件写到私有目录之外。 */
  @Test
  void refusesSymlinkedHandoffDirectory() throws IOException {
    assumeTrue(Files.getFileStore(dataDir).supportsFileAttributeView("posix"));
    Path elsewhere = root.resolve("elsewhere-updates");
    Files.createDirectories(elsewhere);
    Files.createDirectories(updateRoot.getParent());
    Files.createSymbolicLink(updateRoot, elsewhere);

    assertThrows(IOException.class, this::writePreparingHandoff);
    try (Stream<Path> children = Files.list(elsewhere)) {
      assertEquals(
          0, children.count(), "no handoff file may be created outside the private directory");
    }
  }

  /** 结构非法、类型非法或缺失状态的 handoff 一律 UNUSABLE：拒绝复用且绝不下载。 */
  @Test
  void treatsUnusableHandoffMetadataAsNotReusableWithoutDownload() throws IOException {
    Files.createDirectories(updateRoot);
    String matching =
        "\"operationId\":\""
            + OP
            + "\",\"targetVersion\":\"1.0.9\",\"artifactUrl\":\""
            + DaemonUpdateArtifact.artifactUrl("1.0.9")
            + "\",\"artifactSha256\":\""
            + artifactSha
            + "\"";
    for (String metadata :
        new String[] {
          "not-json", "[1,2,3]", "{\"operationId\":\"" + OP + "\"}", "{" + matching + "}"
        }) {
      Files.writeString(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), metadata);
      assertEquals(
          UpdateHandoff.State.UNUSABLE, state(officialCommand(OP, "1.0.9", artifactSha)), metadata);
      ManagedUpdateOutcome outcome =
          updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha));
      assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
      assertEquals(0, fetcher.artifactFetches, "unusable metadata must not trigger a download");
    }
  }

  /** 只允许把既有 handoff 切成 ACCEPTED/FAILED；状态非法或缺元数据都拒绝，绝不静默改写。 */
  @Test
  void rejectsInvalidHandoffTransitions() throws IOException {
    Files.createDirectories(updateRoot);

    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateHandoff.transition(updateRoot, UpdateHandoff.State.PREPARING));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateHandoff.transition(updateRoot, UpdateHandoff.State.ABSENT));
    // 没有可改写的普通文件时直接失败，绝不创建或跟随。
    assertThrows(
        IOException.class,
        () -> UpdateHandoff.transition(updateRoot, UpdateHandoff.State.ACCEPTED));
    assertFalse(Files.exists(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE)));
  }

  /** 下载阶段的 I/O 失败与意外失败都收敛为去敏的确定失败，绝不谎报 PREPARED。 */
  @Test
  void reportsDownloadFailuresWithoutClaimingSuccess() {
    ManagedDaemonUpdater ioFailing =
        new ManagedDaemonUpdater(
            dataDir,
            (source, target) -> {
              throw new IOException("network is down");
            },
            (jar, config) -> true);
    ManagedUpdateOutcome ioOutcome = ioFailing.prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertEquals(
        "update preparation I/O failed",
        assertInstanceOf(ManagedUpdateOutcome.Failed.class, ioOutcome).message());

    ManagedDaemonUpdater crashing =
        new ManagedDaemonUpdater(
            dataDir,
            (source, target) -> {
              throw new IllegalStateException("registrationToken=secret-value");
            },
            (jar, config) -> true);
    ManagedUpdateOutcome crashOutcome = crashing.prepare(officialCommand(OP, "1.0.9", artifactSha));
    assertEquals(
        "update preparation failed",
        assertInstanceOf(ManagedUpdateOutcome.Failed.class, crashOutcome).message());
    assertFalse(crashOutcome instanceof ManagedUpdateOutcome.Prepared);
    assertNoHandoff();
  }

  /** 官方校验文件为空/非法或制品缺少清单都是确定失败：绝不把损坏的输入当成可安装制品。 */
  @Test
  void failsWhenTheChecksumOrManifestIsCorrupted() throws IOException {
    fetcher.checksum = "";
    assertEquals(
        "update preparation I/O failed",
        assertInstanceOf(
                ManagedUpdateOutcome.Failed.class,
                updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha)))
            .message());

    fetcher.checksum = "not-a-sha256-digest  artifact.jar\n";
    assertInstanceOf(
        ManagedUpdateOutcome.Failed.class,
        updater(true).prepare(officialCommand(OP, "1.0.9", artifactSha)));

    byte[] withoutManifest = jarWithoutManifest();
    fetcher.artifact = withoutManifest;
    fetcher.checksum = sha256Hex(withoutManifest) + "  artifact.jar\n";
    assertInstanceOf(
        ManagedUpdateOutcome.Failed.class,
        updater(true).prepare(officialCommand(OP, "1.0.9", sha256Hex(withoutManifest))));

    assertNoHandoff();
  }

  private UpdateHandoff.State state(DaemonUpdateCommand command) {
    try {
      return UpdateHandoff.readState(updateRoot, command);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** 直接以受控参数写出 PREPARING handoff：用于单独验证原子写入的链接安全。 */
  private void writePreparingHandoff() throws IOException {
    UpdateHandoff.writePreparing(
        updateRoot,
        officialCommand(OP, "1.0.9", artifactSha),
        updateRoot.resolve("kk-studio-daemon-update.sh"),
        updateRoot.resolve("staged.jar"),
        installedJar,
        dataDir.resolve(ManagedDaemonUpdater.MANAGED_CONFIG_NAME),
        "/usr/bin/java");
  }

  private void assertNoHandoff() {
    assertFalse(Files.exists(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE)));
  }

  private static byte[] jarWithVersion(String version) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Implementation-Version", version);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
      out.putNextEntry(new JarEntry("placeholder.txt"));
      out.write("x".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return bytes.toByteArray();
  }

  /** 无清单的制品：预检前必须在读取清单处确定失败，绝不能进入安装流程。 */
  private static byte[] jarWithoutManifest() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream out = new JarOutputStream(bytes)) {
      out.putNextEntry(new JarEntry("placeholder.txt"));
      out.write("x".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 本地制品/校验文件写入器：按 URL 后缀区分制品与校验文件，并记录调用次数。 */
  private static final class FakeFetcher implements ManagedDaemonUpdater.ArtifactFetcher {

    private byte[] artifact;
    private String checksum;
    private int artifactFetches;
    private int checksumFetches;

    private FakeFetcher(byte[] artifact, String artifactSha) {
      this.artifact = artifact;
      this.checksum = artifactSha + "  artifact.jar\n";
    }

    @Override
    public void fetch(URI source, Path target) throws IOException {
      try {
        if (source.toString().endsWith(".sha256")) {
          checksumFetches++;
          Files.writeString(target, checksum, StandardCharsets.UTF_8);
        } else {
          artifactFetches++;
          Files.write(target, artifact);
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
  }
}
