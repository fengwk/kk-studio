package fun.fengwk.kkstudio.harness.daemon.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** EnvironmentIdentity 的创建/复用/竞态/损坏/符号链接/父目录替换/根迁移契约测试。 */
class EnvironmentIdentityTest {

  @TempDir Path environmentRoot;

  private static final String WINNER_ID = "123e4567-e89b-12d3-a456-426614174000";

  /** 首次加载创建 canonical UUID 身份文件；重启后复用同一 ID。 */
  @Test
  void createsThenReusesStableIdentityAcrossRestarts() throws IOException {
    EnvironmentId first = EnvironmentIdentity.loadOrCreate(environmentRoot);
    EnvironmentId second = EnvironmentIdentity.loadOrCreate(environmentRoot);

    assertEquals(first, second);
    Path file = environmentRoot.resolve(".kkstudio").resolve(EnvironmentIdentity.FILE_NAME);
    assertTrue(Files.isRegularFile(file));
    assertEquals(first.value(), Files.readString(file, StandardCharsets.UTF_8).strip());
    assertEquals(first, EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** 并发启动必须收敛到同一份身份文件，且文件内容始终是完整 canonical UUID。 */
  @Test
  void concurrentLoadOrCreateConvergesToOneIdentity() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<EnvironmentId> task =
          () -> {
            start.await();
            return EnvironmentIdentity.loadOrCreate(environmentRoot);
          };
      List<Future<EnvironmentId>> futures = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        futures.add(executor.submit(task));
      }
      start.countDown();
      List<String> ids = new ArrayList<>();
      for (Future<EnvironmentId> future : futures) {
        ids.add(future.get(5, TimeUnit.SECONDS).value());
      }
      assertEquals(1, ids.stream().distinct().count());
      String stored =
          Files.readString(
                  environmentRoot.resolve(".kkstudio").resolve(EnvironmentIdentity.FILE_NAME),
                  StandardCharsets.UTF_8)
              .strip();
      assertEquals(ids.get(0), stored);
    } finally {
      executor.shutdownNow();
    }
  }

  /** 环境根整体迁移（move）后身份保持，不生成新 ID。 */
  @Test
  void identitySurvivesEnvironmentRootMove() throws IOException {
    EnvironmentId original = EnvironmentIdentity.loadOrCreate(environmentRoot);
    Path moved = environmentRoot.getParent().resolve(environmentRoot.getFileName() + "-moved");
    Files.move(environmentRoot, moved);
    try {
      assertEquals(original, EnvironmentIdentity.loadOrCreate(moved));
    } finally {
      Files.move(moved, environmentRoot);
    }
  }

  /** 已存在的损坏身份文件必须确定性失败，不能静默重建（避免路由绑定漂移）。 */
  @Test
  void rejectsMalformedIdentityFile() throws IOException {
    Path file = environmentRoot.resolve(".kkstudio").resolve(EnvironmentIdentity.FILE_NAME);
    Files.createDirectories(file.getParent());
    for (String content :
        Set.of(
            "not-a-uuid\n",
            "123e4567-e89b-12d3-a456-426614174000-extra\n",
            "123e4567-E89B-12D3-A456-426614174000\n")) {
      Files.writeString(file, content, StandardCharsets.UTF_8);
      assertThrows(
          IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
    }
  }

  /** 身份文件是符号链接时必须拒绝，防止身份被重定向。 */
  @Test
  void rejectsSymlinkedIdentityFile() throws IOException {
    Path dir = environmentRoot.resolve(".kkstudio");
    Files.createDirectories(dir);
    Path elsewhere = environmentRoot.resolve("elsewhere");
    Files.writeString(elsewhere, WINNER_ID + "\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(dir.resolve(EnvironmentIdentity.FILE_NAME), elsewhere);

    assertThrows(
        IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** CRLF 行尾与尾部换行是合法持久化格式；其余空白必须拒绝。 */
  @Test
  void acceptsCrlfLineEndingAndRejectsSurroundingWhitespace() throws IOException {
    Path dir = environmentRoot.resolve(".kkstudio");
    Files.createDirectories(dir);
    Path file = dir.resolve(EnvironmentIdentity.FILE_NAME);

    Files.writeString(file, WINNER_ID + "\r\n", StandardCharsets.UTF_8);
    assertEquals(WINNER_ID, EnvironmentIdentity.loadOrCreate(environmentRoot).value());

    Files.writeString(file, " " + WINNER_ID + "\n", StandardCharsets.UTF_8);
    assertThrows(
        IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** 预置的 .kkstudio 目录权限过松时，加载后强制收紧为 owner-only，身份文件同属私有。 */
  @Test
  void enforcesPrivatePermissionsOnPreExistingDirectory() throws IOException {
    assumeTrue(Files.getFileStore(environmentRoot).supportsFileAttributeView("posix"));
    Path dir = environmentRoot.resolve(".kkstudio");
    Files.createDirectories(dir);
    Files.setPosixFilePermissions(
        dir,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE));

    EnvironmentId id = EnvironmentIdentity.loadOrCreate(environmentRoot);

    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE),
        Files.getPosixFilePermissions(dir));
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(dir.resolve(EnvironmentIdentity.FILE_NAME)));
    assertEquals(id, EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** 身份目录被普通文件占据时，创建失败必须确定性失败并抛出 IllegalStateException。 */
  @Test
  void failsWhenIdentityDirectoryIsBlockedByAFile() throws IOException {
    Path blocked = environmentRoot.resolve("blocked");
    Files.createDirectories(blocked);
    Files.writeString(blocked.resolve(".kkstudio"), "occupied", StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(blocked));
  }

  /** .kkstudio 是符号链接（指向攻击者目录）时拒绝，即使其中已有合法身份文件。 */
  @Test
  void rejectsSymlinkedIdentityDirectory() throws IOException {
    Path attacker = environmentRoot.resolve("attacker");
    Files.createDirectories(attacker);
    Files.writeString(
        attacker.resolve(EnvironmentIdentity.FILE_NAME), WINNER_ID + "\n", StandardCharsets.UTF_8);
    Files.createSymbolicLink(environmentRoot.resolve(".kkstudio"), attacker);
    assertThrows(
        IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /**
   * provider 不提供 secure directory stream 时失败关闭（zipfs 的 DirectoryStream 不是 SecureDirectoryStream）。
   */
  @Test
  void openSecureDirectoryFailsClosedWithoutSecureStreamSupport() throws IOException {
    Path zipFile = environmentRoot.resolve("plain.zip");
    try (FileSystem zipfs =
        FileSystems.newFileSystem(URI.create("jar:" + zipFile.toUri()), Map.of("create", "true"))) {
      Path dir = Files.createDirectory(zipfs.getPath("/d"));
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class, () -> EnvironmentIdentity.openSecureDirectory(dir));
      assertTrue(error.getMessage().contains("does not support secure directory streams"));
    }
  }

  /** 根目录 stat 为目录但真实路径解析失败（如目录在两步之间消失）时必须失败关闭，不得把身份写入未固定根。 */
  @Test
  void loadOrCreateFailsClosedWhenRootRealPathCannotResolve() throws IOException {
    Path real = Files.createDirectory(environmentRoot.resolve("real-root"));
    Path proxied = TestDelegatingPath.wrap(real, true);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(proxied));
    assertTrue(error.getMessage().contains("environment root must be an existing directory"));
    // 失败路径上不得产生任何身份文件副作用。
    assertTrue(!Files.exists(real.resolve(".kkstudio")));
  }

  /** 环境根不存在或不是目录时拒绝（身份绝不写入未知根）。 */
  @Test
  void rejectsMissingOrNonDirectoryEnvironmentRoot() throws IOException {
    assertThrows(
        IllegalStateException.class,
        () -> EnvironmentIdentity.loadOrCreate(environmentRoot.resolve("missing")));
    Path fileRoot = environmentRoot.resolve("file-root");
    Files.writeString(fileRoot, "x", StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(fileRoot));
  }

  /** 发布后身份文件被替换为符号链接（final symlink）时拒绝，读取不跟随链接。 */
  @Test
  void rejectsFinalIdentityFileSymlinkAfterCreation() throws IOException {
    EnvironmentIdentity.loadOrCreate(environmentRoot);
    Path file = environmentRoot.resolve(".kkstudio").resolve(EnvironmentIdentity.FILE_NAME);
    Path elsewhere = environmentRoot.resolve("elsewhere");
    Files.writeString(elsewhere, WINNER_ID + "\n", StandardCharsets.UTF_8);
    Files.delete(file);
    Files.createSymbolicLink(file, elsewhere);
    assertThrows(
        IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** 身份文件超过小字节上限（日志污染/损坏）时拒绝，不分配无界缓冲。 */
  @Test
  void rejectsOversizedIdentityFile() throws IOException {
    Path dir = environmentRoot.resolve(".kkstudio");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(EnvironmentIdentity.FILE_NAME), "x".repeat(100) + "\n", StandardCharsets.UTF_8);
    assertThrows(
        IllegalStateException.class, () -> EnvironmentIdentity.loadOrCreate(environmentRoot));
  }

  /** 发布路径必须 create-only：目标已存在时保留先到者内容，新目标按 canonical UUID+换行创建。 */
  @Test
  void publishNewIdentityIsCreateOnly() throws IOException {
    Path dir = environmentRoot.resolve(".kkstudio");
    Files.createDirectories(dir);
    Path identityFile = dir.resolve(EnvironmentIdentity.FILE_NAME);

    // 目标已存在：内容保持原样，不被覆盖。
    Files.writeString(identityFile, WINNER_ID + "\n", StandardCharsets.UTF_8);
    try (SecureDirectoryStream<Path> stream = EnvironmentIdentity.openSecureDirectory(dir)) {
      EnvironmentIdentity.publishNewIdentity(stream, EnvironmentIdentity.FILE_NAME);
    }
    assertEquals(WINNER_ID + "\n", Files.readString(identityFile, StandardCharsets.UTF_8));

    // 目标不存在：按 canonical UUID + 换行创建。
    Path fresh = dir.resolve("fresh-identity");
    try (SecureDirectoryStream<Path> stream = EnvironmentIdentity.openSecureDirectory(dir)) {
      EnvironmentIdentity.publishNewIdentity(stream, "fresh-identity");
    }
    assertEquals(37, Files.size(fresh));
    assertEquals(36, Files.readString(fresh, StandardCharsets.UTF_8).strip().length());
  }

  /** 父目录条目在句柄打开后被替换为符号链接：NOFOLLOW 相对打开必须确定性失败，读写无法被重定向。 */
  @Test
  void openChildDirectoryRejectsReplacedParentEntry() throws IOException {
    Path holder = environmentRoot.resolve("holder");
    Files.createDirectories(holder);
    Path realChild = holder.resolve(".kkstudio");
    Files.createDirectories(realChild);
    try (SecureDirectoryStream<Path> rootStream = EnvironmentIdentity.openSecureDirectory(holder)) {
      // 打开根句柄后，子条目被重命名并替换为符号链接。
      Files.move(realChild, holder.resolve("moved-away"));
      Files.createSymbolicLink(realChild, holder.resolve("moved-away"));
      assertThrows(
          IOException.class, () -> EnvironmentIdentity.openChildDirectory(rootStream, ".kkstudio"));
    }
  }

  /** 两次独立根得到不同 ID；身份文件内容为 canonical 小写 UUID。 */
  @Test
  void distinctRootsGetDistinctIds() throws IOException {
    Path other = Files.createTempDirectory("identity-other");
    try {
      EnvironmentId first = EnvironmentIdentity.loadOrCreate(environmentRoot);
      EnvironmentId second = EnvironmentIdentity.loadOrCreate(other);
      assertNotEquals(first, second);
    } finally {
      deleteRecursively(other);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // best-effort cleanup
                }
              });
    }
  }
}
