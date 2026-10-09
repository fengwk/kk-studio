package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 针对 {@link TextOutputStore} 的行为断言测试：受控临时 workspace 登记、owner-only 中转、原子发布与已发布全文的保留语义。
 *
 * <p>本地大文本是模型可继续读取的 durable 事实，因此这里重点证明：每次外化都落在登记过的 {@code tmp/workspaces/<uuid>} 内、中转文件永远以 0600
 * 创建并原子发布为 {@code .log}、发布后的文件不会因组件内部任何操作而消失，以及目录布局与捕获预算等配置在非法输入下 fail closed。
 */
class TextOutputStoreTest {

  @TempDir Path root;

  private TextOutputStore open() {
    return TextOutputStore.open(root.resolve("tmp"));
  }

  /** 打开必须创建受控 workspace 根并规范化为绝对路径，且目录布局在非法捕获预算下 fail closed。 */
  @Test
  void openCreatesWorkspacesRootAsAbsolutePath() {
    TextOutputStore store = open();

    assertTrue(Files.isDirectory(store.root(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(store.root().isAbsolute());
    assertTrue(store.root().toString().endsWith("workspaces"));
    assertEquals(TextOutputStore.DEFAULT_CAPTURE_BUDGET_BYTES, store.captureBudgetBytes());
    assertEquals(1024L * 1024 * 1024, TextOutputStore.DEFAULT_CAPTURE_BUDGET_BYTES);

    assertThrows(IllegalArgumentException.class, () -> TextOutputStore.open(root.resolve("t"), 0));
    assertThrows(IllegalArgumentException.class, () -> TextOutputStore.open(root.resolve("t"), -1));
  }

  /** 中转文件名必须落在自己登记的 workspace 内、以 .part 结尾，且调用标识中的不安全字符被收敛而不是穿透为路径。 */
  @Test
  void createStagingFileSanitizesCallIdInsideItsWorkspace() throws IOException {
    TextOutputStore store = open();

    Path stagingFile = store.createStagingFile("call-1");
    Path workspace = stagingFile.getParent();
    assertEquals(store.root(), workspace.getParent(), "workspace 必须是受控根的直接子目录");
    assertTrue(stagingFile.getFileName().toString().endsWith(".part"));
    assertTrue(stagingFile.getFileName().toString().startsWith("call-1-"));
    assertTrue(Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS));

    // 路径分隔符与空白都被替换为 '_'，因此不会逃出受控根。
    Path escaped = store.createStagingFile("../../etc/passwd");
    assertTrue(escaped.normalize().startsWith(store.root()), "中转文件不得逃出受控根：" + escaped);
    assertEquals(1, escaped.getFileName().getNameCount(), "文件名不得含路径分隔符");

    // 空调用标识回退到稳定前缀而不是空文件名。
    Path fallback = store.createStagingFile("");
    assertTrue(fallback.getFileName().toString().startsWith("output-"));
  }

  /** 同名并发创建必须靠随机后缀避免互相覆盖：两次调用得到不同文件，且两个文件都真实存在。 */
  @Test
  void createStagingFileNeverReusesAnExistingName() throws IOException {
    TextOutputStore store = open();

    Set<String> names = new HashSet<>();
    for (int index = 0; index < 16; index++) {
      names.add(store.createStagingFile("same-call").getFileName().toString());
    }

    assertEquals(16, names.size(), "每次创建都必须得到唯一的中转文件名");
    assertEquals(16, store.partialFiles().size(), "每个中转文件都落在自己登记的 workspace 内");
  }

  /** 中转文件在 POSIX 上必须直接以 0600 创建，workspace 目录收敛为 0700。 */
  @Test
  void stagingFileAndWorkspaceAreOwnerOnlyOnPosix() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    TextOutputStore store = open();

    Path stagingFile = store.createStagingFile("secret-output");

    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(stagingFile),
        "中转文件必须以 owner-only 0600 创建");
    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE),
        Files.getPosixFilePermissions(stagingFile.getParent()),
        "workspace 目录必须为 owner-only 0700");
    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE),
        Files.getPosixFilePermissions(store.root()),
        "受控根必须为 owner-only 0700");
  }

  /** 发布只做一次原子 move：part 变成同 workspace 内的 .log，内容不变，且 part 不再存在。 */
  @Test
  void publishMovesStagingFileToPublishedLogAtomically() throws IOException {
    TextOutputStore store = open();
    Path stagingFile = store.createStagingFile("publish-me");
    Files.writeString(stagingFile, "line 1\nline 2\n");

    Path published = store.publish(stagingFile);

    assertEquals(stagingFile.getParent(), published.getParent(), "发布应与中转文件同 workspace");
    assertTrue(published.isAbsolute());
    assertTrue(published.getFileName().toString().endsWith(".log"));
    assertFalse(published.getFileName().toString().endsWith(".part"));
    assertEquals("line 1\nline 2\n", Files.readString(published));
    assertFalse(Files.exists(stagingFile), "发布后不得残留 .part 文件");
    assertEquals(List.of(published), store.publishedFiles());
  }

  /** 发布后的文件名必须保留调用标识与唯一后缀，使并发调用的全文不会互相覆盖。 */
  @Test
  void publishPreservesCallIdAndUniqueness() throws IOException {
    TextOutputStore store = open();
    Path first = store.createStagingFile("call-abc");
    Path second = store.createStagingFile("call-abc");

    Path firstPublished = store.publish(first);
    Path secondPublished = store.publish(second);

    assertNotEquals(firstPublished, secondPublished);
    assertTrue(firstPublished.getFileName().toString().startsWith("call-abc-"));
    assertTrue(secondPublished.getFileName().toString().startsWith("call-abc-"));
  }

  /** 非 .part 输入必须被拒绝，避免把任意文件（例如已发布全文）误当作中转文件搬移或覆盖。 */
  @Test
  void publishRejectsNonStagingFile() throws IOException {
    TextOutputStore store = open();
    Path workspace = store.root().resolve("11111111-1111-1111-1111-111111111111");
    Files.createDirectories(workspace);
    Path alreadyPublished = Files.writeString(workspace.resolve("done.log"), "x");

    assertThrows(IllegalArgumentException.class, () -> store.publish(alreadyPublished));
    assertEquals("x", Files.readString(alreadyPublished), "被拒绝的输入不得被搬移或改写");
  }

  /** 清理中转文件不得触碰已发布全文：durable history 可能仍引用这些路径。 */
  @Test
  void deleteStagingQuietlyLeavesPublishedTextUntouched() throws IOException {
    TextOutputStore store = open();
    Path stagingFile = store.createStagingFile("cleanup");
    Files.writeString(stagingFile, "payload");
    Path published = store.publish(stagingFile);
    Path leftover = store.createStagingFile("leftover");
    Files.writeString(leftover, "partial");

    store.deleteStagingQuietly(leftover);

    assertFalse(Files.exists(leftover), "未发布的中转文件应被清理");
    assertFalse(Files.exists(leftover.getParent()), "取消/失败后整个中转 workspace 必须被立即回收，不残留受控残留");
    assertTrue(Files.exists(published), "已发布全文不得被清理");
    assertEquals("payload", Files.readString(published));
    assertEquals(List.of(published), store.publishedFiles());
    assertTrue(store.partialFiles().isEmpty());

    // 清理不存在的路径与 null 都是幂等空操作。
    store.deleteStagingQuietly(leftover);
    store.deleteStagingQuietly(null);
    assertTrue(Files.exists(published));
  }

  /** 可信根之下符号链接（tmp 分量）必须失败关闭，绝不跟随链接进入受控根。 */
  @Test
  void openRejectsSymlinkedTempComponent() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    Path real = Files.createDirectories(root.resolve("real-tmp"));
    Path link = root.resolve("tmp");
    Files.createSymbolicLink(link, real);

    assertThrows(UncheckedIOException.class, () -> TextOutputStore.open(link));
  }

  /** acquire 只对登记 workspace 内的路径授予租约；受控根外路径返回 no-op。 */
  @Test
  void acquireOnlyProtectsControlledTemporaryPaths() throws IOException {
    TextOutputStore store = open();
    Path staging = store.createStagingFile("lease");
    Files.writeString(staging, "content");
    Path published = store.publish(staging);

    TextOutputStore.Lease lease = store.acquire(published);
    assertNotSame(TextOutputStore.Lease.NONE, lease, "已发布受控全文必须授予真实租约");
    lease.close();
    assertSame(TextOutputStore.Lease.NONE, store.acquire(root.resolve("outside.log")));
  }

  /** 列举只下探 workspace 目录：受控根下的散落文件（即使后缀相同）不计入已发布/中转全文。 */
  @Test
  void listingSkipsEntriesThatAreNotWorkspaceDirectories() throws IOException {
    TextOutputStore store = open();
    Path stray = Files.writeString(store.root().resolve("stray.log"), "not-a-workspace");
    Path staging = store.createStagingFile("real");
    Files.writeString(staging, "payload");
    Path published = store.publish(staging);

    assertEquals(List.of(published), store.publishedFiles());
    assertTrue(Files.exists(stray), "列举不得移动或删除散落文件");
    assertTrue(store.partialFiles().isEmpty());
  }

  /** 列举无法读取受控根时必须显式失败，绝不静默返回空列表掩盖真实故障。 */
  @Test
  void listingReportsUnreadableRoot() throws IOException {
    TextOutputStore store = open();
    Files.delete(store.root());

    assertThrows(UncheckedIOException.class, store::publishedFiles);
  }

  private static boolean supportsSymlinks() {
    Path probe = null;
    try {
      Path base = Files.createTempDirectory("symlink-probe");
      Path target = Files.createDirectories(base.resolve("target"));
      probe = base.resolve("link");
      Files.createSymbolicLink(probe, target);
      return true;
    } catch (IOException | UnsupportedOperationException error) {
      return false;
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
          Files.deleteIfExists(probe.getParent());
        } catch (IOException ignored) {
          // 探测目录残留不影响测试结论。
        }
      }
    }
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
}
