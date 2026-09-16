package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * 针对 {@link TextOutputStore} 的行为断言测试：owner-only 暂存、原子发布与 durable 全文的不可删除语义。
 *
 * <p>本地大文本是模型可继续读取的 durable 事实，因此这里重点证明三件事：中转文件永远以 0600 创建且可被原子发布为 {@code
 * .log}、发布后的文件不会因为组件内部任何操作而消失、 以及目录布局与捕获预算等配置项在非法输入下 fail closed。
 */
class TextOutputStoreTest {

  @TempDir Path root;

  private TextOutputStore open() {
    return TextOutputStore.open(root.resolve("text"), root.resolve("staging"));
  }

  /** 打开必须创建 text 与 staging 两个目录，并把它们规范化为绝对路径。 */
  @Test
  void openCreatesBothDirectoriesAsAbsolutePaths() {
    TextOutputStore store = open();

    assertTrue(Files.isDirectory(store.textDirectory(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isDirectory(store.stagingDirectory(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(store.textDirectory().isAbsolute());
    assertTrue(store.stagingDirectory().isAbsolute());
    assertEquals(TextOutputStore.DEFAULT_CAPTURE_BUDGET_BYTES, store.captureBudgetBytes());
    assertEquals(1024L * 1024 * 1024, TextOutputStore.DEFAULT_CAPTURE_BUDGET_BYTES);
  }

  /** 非正的捕获预算必须在打开期拒绝，避免“立即截断”这种静默失效的配置进入运行期。 */
  @Test
  void openRejectsNonPositiveCaptureBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TextOutputStore.open(root.resolve("t"), root.resolve("s"), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> TextOutputStore.open(root.resolve("t"), root.resolve("s"), -1));
  }

  /** 中转文件名必须落在 staging 目录内、以 .part 结尾，且调用标识中的不安全字符被收敛而不是穿透为路径。 */
  @Test
  void createStagingFileSanitizesCallIdIntoStagingDirectory() throws IOException {
    TextOutputStore store = open();

    Path stagingFile = store.createStagingFile("call-1");
    assertEquals(store.stagingDirectory(), stagingFile.getParent());
    assertTrue(stagingFile.getFileName().toString().endsWith(".part"));
    assertTrue(stagingFile.getFileName().toString().startsWith("call-1-"));
    assertTrue(Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS));

    // 路径分隔符与空白都被替换为 '_'，因此不会逃出 staging 目录。
    Path escaped = store.createStagingFile("../../etc/passwd");
    assertEquals(store.stagingDirectory(), escaped.getParent());
    assertEquals(store.stagingDirectory(), escaped.normalize().getParent());

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
    try (var entries = Files.list(store.stagingDirectory())) {
      assertEquals(16, entries.count());
    }
  }

  /** 中转文件在 POSIX 上必须直接以 0600 创建，不依赖进程 umask（umask 022 下 0644 会让同机其他用户读到输出）。 */
  @Test
  void stagingFileIsOwnerOnlyOnPosix() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    TextOutputStore store = open();

    Path stagingFile = store.createStagingFile("secret-output");

    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(stagingFile),
        "中转文件必须以 owner-only 0600 创建");
  }

  /** text 与 staging 目录在 POSIX 上收敛为 0700，避免同机其他用户枚举或读取 durable 全文。 */
  @Test
  void directoriesAreOwnerOnlyOnPosix() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    TextOutputStore store = open();

    Set<PosixFilePermission> ownerOnly =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    assertEquals(ownerOnly, Files.getPosixFilePermissions(store.textDirectory()));
    assertEquals(ownerOnly, Files.getPosixFilePermissions(store.stagingDirectory()));
  }

  /** 发布只做一次原子 move：part 变成同名 .log，内容不变，且 part 不再存在。 */
  @Test
  void publishMovesStagingFileToTextDirectoryAtomically() throws IOException {
    TextOutputStore store = open();
    Path stagingFile = store.createStagingFile("publish-me");
    Files.writeString(stagingFile, "line 1\nline 2\n");

    Path published = store.publish(stagingFile);

    assertEquals(store.textDirectory(), published.getParent());
    assertTrue(published.getFileName().toString().endsWith(".log"));
    assertFalse(published.getFileName().toString().endsWith(".part"));
    assertEquals("line 1\nline 2\n", Files.readString(published));
    assertFalse(Files.exists(stagingFile), "发布后不得残留 .part 文件");
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
    Path alreadyPublished = Files.writeString(store.textDirectory().resolve("done.log"), "x");

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
    assertTrue(Files.exists(published), "已发布全文不得被清理");
    assertEquals("payload", Files.readString(published));

    // 清理不存在的路径与 null 都是幂等空操作。
    store.deleteStagingQuietly(leftover);
    store.deleteStagingQuietly(null);
    assertTrue(Files.exists(published));
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
}
