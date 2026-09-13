package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@link DaemonSkillStore} 单元测试： 验证目录布局创建、checkout 路径段与 commit ID 校验、正文 blob 写入/去重/读取/可读性探测、
 * manifest 原子写入与清理、以及 staging 目录分配。
 */
class DaemonSkillStoreTest {

  private static final UUID SOURCE_ID = DaemonSkillTestSupport.SOURCE_ID;
  private static final String COMMIT_40 = "1".repeat(40);
  private static final String COMMIT_64 = "a".repeat(64);

  @TempDir Path tempDir;

  private Path dataDir;
  private DaemonSkillStore store;

  @BeforeEach
  void setUp() throws IOException {
    dataDir = tempDir.resolve("data");
    store = DaemonSkillStore.open(dataDir);
  }

  /** 测试意图：open 方法必须正确建立 bodies、checkouts 与 staging 三个子目录。 */
  @Test
  void openCreatesExpectedDirectoryLayout() {
    assertTrue(Files.isDirectory(dataDir.resolve("skills/bodies")));
    assertTrue(Files.isDirectory(dataDir.resolve("skills/checkouts")));
    assertTrue(Files.isDirectory(dataDir.resolve("skills/staging")));
    assertEquals(dataDir.resolve("skills/checkouts"), store.checkoutsRoot());
    assertEquals(dataDir.resolve("skills/staging"), store.stagingRoot());
  }

  /** 测试意图：checkout 方法只接受小写 40 或 64 位 commit ID，杜绝路径穿越与非规范输入。 */
  @Test
  void checkoutValidatesCommitIdAndResolvesUnderCheckoutsRoot() {
    Path valid40 = store.checkout(SOURCE_ID, COMMIT_40);
    assertEquals(
        dataDir.resolve("skills/checkouts").resolve(SOURCE_ID.toString()).resolve(COMMIT_40),
        valid40);

    Path valid64 = store.checkout(SOURCE_ID, COMMIT_64);
    assertEquals(
        dataDir.resolve("skills/checkouts").resolve(SOURCE_ID.toString()).resolve(COMMIT_64),
        valid64);

    // 非规范 commit ID：大写、包含非十六进制、路径穿越、过短
    for (String invalid :
        List.of(
            "1".repeat(39),
            "A".repeat(40),
            "../escape",
            "not-hex-id-with-length-40----------------",
            "")) {
      DaemonSkillException error =
          assertThrows(
              DaemonSkillException.class,
              () -> store.checkout(SOURCE_ID, invalid),
              "expected rejection for: " + invalid);
      assertTrue(error.getMessage().contains("commit id"));
    }

    assertThrows(NullPointerException.class, () -> store.checkout(null, COMMIT_40));
  }

  /** 测试意图：manifest 文件缺失时 readManifest 返回 empty manifest，存在时返回已解码内容。 */
  @Test
  void readManifestReturnsEmptyWhenMissingAndDecodesWhenPresent() throws IOException {
    DaemonSkillManifest empty = store.readManifest();
    assertEquals(0L, empty.sourceSetVersion());
    assertTrue(empty.sources().isEmpty());
    assertTrue(empty.retained().isEmpty());

    // 写入一个新 manifest 并读回
    DaemonSkillManifest manifest = DaemonSkillManifest.empty();
    store.writeManifest(manifest);
    DaemonSkillManifest read = store.readManifest();
    assertEquals(manifest.version(), read.version());
    assertEquals(manifest.sourceSetVersion(), read.sourceSetVersion());
  }

  /** 测试意图：writeBody 对同一 revision 是幂等的，当目标文件已存在时直接返回而不覆盖。 */
  @Test
  void writeBodyIgnoresExistingFile() throws IOException {
    String revision = COMMIT_64;
    store.writeBody(revision, "first body");
    assertEquals(Optional.of("first body"), store.readBody(revision));

    // 再次写入不同内容，原文件内容保持不变
    store.writeBody(revision, "second body");
    assertEquals(Optional.of("first body"), store.readBody(revision));
  }

  /** 测试意图：readBody 在文件不存在或目标不是常规文件（如目录）时返回 Optional.empty()。 */
  @Test
  void readBodyReturnsEmptyForMissingOrNonRegularFiles() throws IOException {
    assertEquals(Optional.empty(), store.readBody(COMMIT_64));

    // 目标路径为目录时读取失败，安全返回 empty 而不抛出异常
    Path notAFile = dataDir.resolve("skills/bodies/" + COMMIT_64 + ".md");
    Files.createDirectory(notAFile);
    assertEquals(Optional.empty(), store.readBody(COMMIT_64));
  }

  /** 测试意图：writeBody 发生异常时，未发布的临时文件被 finally 安全清理。 */
  @Test
  void writeBodyCleansUpTemporaryFileOnFailure() throws IOException {
    String revision = "d".repeat(64);
    // 将 target 创建为非空目录，使 moveAtomically 失败
    Path targetDir = dataDir.resolve("skills/bodies/" + revision + ".md");
    Files.createDirectory(targetDir);
    Files.createFile(targetDir.resolve("child"));

    assertThrows(IOException.class, () -> store.writeBody(revision, "content"));

    // 确认 bodies 目录下没有遗留 body-*.tmp 临时文件
    try (Stream<Path> files = Files.list(dataDir.resolve("skills/bodies"))) {
      long tmpCount = files.filter(p -> p.getFileName().toString().startsWith("body-")).count();
      assertEquals(0, tmpCount, "temporary body files must be cleaned up on failure");
    }
  }

  /** 测试意图：isBodyReadable 对常规非空文件、0 字节空文件返回 true，对不存在路径或目录返回 false。 */
  @Test
  void isBodyReadableDetectsFileStatusSafely() throws IOException {
    String revisionNonEmpty = "a".repeat(64);
    String revisionEmpty = "b".repeat(64);
    String revisionDir = "c".repeat(64);
    String revisionUnreadable = "e".repeat(64);

    assertFalse(store.isBodyReadable(revisionNonEmpty));

    // 非空文件
    store.writeBody(revisionNonEmpty, "hello");
    assertTrue(store.isBodyReadable(revisionNonEmpty));

    // 0 字节文件
    Path emptyTarget = dataDir.resolve("skills/bodies/" + revisionEmpty + ".md");
    Files.createFile(emptyTarget);
    assertTrue(store.isBodyReadable(revisionEmpty));

    // 目录
    Path dirTarget = dataDir.resolve("skills/bodies/" + revisionDir + ".md");
    Files.createDirectory(dirTarget);
    assertFalse(store.isBodyReadable(revisionDir));

    // 不可读常规文件（若 OS 支持权限设置且对当前进程生效）
    Path unreadableFile = dataDir.resolve("skills/bodies/" + revisionUnreadable + ".md");
    Files.writeString(unreadableFile, "secret");
    if (unreadableFile.toFile().setReadable(false) && !Files.isReadable(unreadableFile)) {
      try {
        assertFalse(store.isBodyReadable(revisionUnreadable));
        assertEquals(Optional.empty(), store.readBody(revisionUnreadable));
      } finally {
        unreadableFile.toFile().setReadable(true);
      }
    }
  }

  /** 测试意图：writeManifest 发生写入或移动异常时，未发布的临时文件被 finally 安全清理。 */
  @Test
  void writeManifestCleansUpTemporaryFileOnFailure() throws IOException {
    // 将 manifest.json 创建为非空目录，使得原子 move 失败
    Path manifestPath = dataDir.resolve("skills/manifest.json");
    Files.createDirectory(manifestPath);
    Files.createFile(manifestPath.resolve("blocking-file"));

    assertThrows(IOException.class, () -> store.writeManifest(DaemonSkillManifest.empty()));

    // 确认 skills 目录下没有遗留 manifest-*.tmp 临时文件
    try (Stream<Path> files = Files.list(dataDir.resolve("skills"))) {
      long tmpCount = files.filter(p -> p.getFileName().toString().startsWith("manifest-")).count();
      assertEquals(0, tmpCount, "temporary files must be cleaned up on failure");
    }
  }

  /** 测试意图：newStagingDirectory 分配的临时目录位于 stagingRoot 下且具有独立前缀。 */
  @Test
  void newStagingDirectoryCreatesIsolatedDirectories() throws IOException {
    Path staging1 = store.newStagingDirectory("git-fetch");
    Path staging2 = store.newStagingDirectory("git-fetch");

    assertTrue(Files.isDirectory(staging1));
    assertTrue(Files.isDirectory(staging2));
    assertTrue(staging1.startsWith(store.stagingRoot()));
    assertTrue(staging2.startsWith(store.stagingRoot()));
    assertFalse(staging1.equals(staging2));

    Files.deleteIfExists(staging1);
    Files.deleteIfExists(staging2);
  }
}
