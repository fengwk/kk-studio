package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** 意图：受管更新准备只在真实受管布局上工作，校验官方 URL/SHA256/manifest/预检，失败保留旧二进制，同一 operation 复用不重复下载， 且拒绝符号链接逃逸。 */
class ManagedDaemonUpdaterTest {

  private static final String OP = "11111111-1111-1111-1111-111111111111";

  @TempDir Path root;

  private Path dataDir;
  private Path installedJar;
  private FakeFetcher fetcher;
  private byte[] artifact;
  private String artifactSha;

  @BeforeEach
  void setUp() throws IOException {
    dataDir = root.resolve("data");
    Path lib = dataDir.resolve("lib");
    Files.createDirectories(lib);
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

  private static DaemonUpdateCommand command(String version, String url, String sha) {
    return new DaemonUpdateCommand(OP, version, url, sha);
  }

  private static DaemonUpdateCommand officialCommand(String version, String sha) {
    return command(version, DaemonUpdateArtifact.artifactUrl(version), sha);
  }

  @Test
  void preparesHandoffAndLauncherInputs() {
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand("1.0.9", artifactSha));

    ManagedUpdateOutcome.Prepared prepared =
        assertInstanceOf(ManagedUpdateOutcome.Prepared.class, outcome);
    assertEquals("1.0.9", prepared.targetVersion());
    assertEquals(installedJar, prepared.installedJar());
    assertTrue(
        Files.isRegularFile(
            prepared.handoffDirectory().resolve(ManagedDaemonUpdater.HANDOFF_FILE)));
    assertTrue(Files.isRegularFile(prepared.stagedJar()));
    assertTrue(Files.isRegularFile(prepared.updateScript()));
    assertTrue(Files.isExecutable(prepared.updateScript()));
    assertEquals(dataDir.resolve("updates").resolve(OP), prepared.handoffDirectory());
  }

  @Test
  void reusesExistingHandoffWithoutSecondDownload() throws IOException {
    ManagedUpdateOutcome first = updater(true).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Prepared.class, first);
    assertEquals(1, fetcher.artifactFetches);

    ManagedUpdateOutcome second = updater(true).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Prepared.class, second);
    assertEquals(1, fetcher.artifactFetches, "repeated operation must not download again");
  }

  @Test
  void failsWhenDownloadedShaDoesNotMatchCommand() {
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand("1.0.9", "b".repeat(64)));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertNoHandoff();
  }

  @Test
  void failsWhenOfficialChecksumDoesNotMatch() {
    fetcher.checksum = "c".repeat(64) + "  artifact.jar\n";
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertNoHandoff();
  }

  @Test
  void failsWhenManifestVersionDoesNotMatch() throws IOException {
    byte[] other = jarWithVersion("1.0.8");
    fetcher.artifact = other;
    fetcher.checksum = sha256Hex(other) + "  artifact.jar\n";
    ManagedUpdateOutcome outcome =
        updater(true).prepare(officialCommand("1.0.9", sha256Hex(other)));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenPreflightRejectsConfiguration() {
    ManagedUpdateOutcome outcome = updater(false).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenArtifactUrlDoesNotMatchTargetVersion() {
    ManagedUpdateOutcome outcome =
        updater(true)
            .prepare(command("1.0.9", DaemonUpdateArtifact.artifactUrl("1.0.8"), artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void failsWhenManagedLayoutIsMissing() throws IOException {
    Files.delete(installedJar);
    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
  }

  @Test
  void refusesSymlinkedManagedArtifact() throws IOException {
    assumeTrue(Files.getFileStore(dataDir).supportsFileAttributeView("posix"));
    Path elsewhere = root.resolve("elsewhere.jar");
    Files.write(elsewhere, new byte[] {9});
    Files.delete(installedJar);
    Files.createSymbolicLink(installedJar, elsewhere);

    ManagedUpdateOutcome outcome = updater(true).prepare(officialCommand("1.0.9", artifactSha));
    assertInstanceOf(ManagedUpdateOutcome.Failed.class, outcome);
    assertFalse(Files.exists(dataDir.resolve("updates").resolve(OP)));
  }

  private void assertNoHandoff() {
    assertFalse(
        Files.exists(
            dataDir.resolve("updates").resolve(OP).resolve(ManagedDaemonUpdater.HANDOFF_FILE)));
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
