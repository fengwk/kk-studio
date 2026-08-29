package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Environment Root 单层目录浏览的路径边界、排序、上限与 git 分支契约。 */
class EnvironmentDirectoryBrowserTest {

  @TempDir Path root;

  private EnvironmentDirectoryBrowser browser() {
    return new EnvironmentDirectoryBrowser(root);
  }

  @Test
  void listsRootWithDotAndReturnsCanonicalWirePaths() throws Exception {
    Files.createDirectories(root.resolve("src/main"));
    Files.createDirectories(root.resolve("src/test"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("README.md"), "x");
    Files.writeString(root.resolve("src/main/App.java"), "x");

    EnvironmentDirectoryListing listed = browser().list(".");

    assertEquals(".", listed.path());
    assertEquals(".", listed.displayPath());
    assertEquals(".", listed.parentPath());
    assertFalse(listed.truncated());
    assertNull(listed.gitBranch());
    assertEquals(
        List.of("docs", "src"),
        listed.entries().stream().map(EnvironmentDirectoryEntry::name).toList());
    assertEquals(
        List.of("docs", "src"),
        listed.entries().stream().map(EnvironmentDirectoryEntry::path).toList());

    EnvironmentDirectoryListing nested = browser().list("src");
    assertEquals("src", nested.path());
    assertEquals("src", nested.displayPath());
    assertEquals(".", nested.parentPath());
    // 条目 path 是请求目录的直接子路径（wire），name 是目录名。
    assertEquals(
        List.of("src/main", "src/test"),
        nested.entries().stream().map(EnvironmentDirectoryEntry::path).toList());
    assertEquals(
        List.of("main", "test"),
        nested.entries().stream().map(EnvironmentDirectoryEntry::name).toList());

    EnvironmentDirectoryListing deep = browser().list("src/main");
    assertEquals("src/main", deep.path());
    assertEquals("main", deep.displayPath());
    assertEquals("src", deep.parentPath());
    assertTrue(deep.entries().isEmpty());
  }

  /** 条目只含真实目录：文件被过滤，symlink（含指向目录的）默认不暴露。 */
  @Test
  void exposesOnlyRealDirectoriesAndSkipsSymlinks() throws Exception {
    Path outside = Files.createTempDirectory("browser-outside-dir");
    try {
      Files.createDirectories(root.resolve("real"));
      Files.createSymbolicLink(root.resolve("linked"), root.resolve("real"));
      Files.createSymbolicLink(root.resolve("broken-link"), root.resolve("missing"));
      Files.createSymbolicLink(root.resolve("out-link"), outside);
      Files.writeString(root.resolve("file.txt"), "x");

      EnvironmentDirectoryListing listed = browser().list(".");
      assertEquals(
          List.of("real"), listed.entries().stream().map(EnvironmentDirectoryEntry::path).toList());
    } finally {
      deleteRecursively(outside);
    }
  }

  /** symlink 穿越到 root 之外时，最终 toRealPath 判定越界并拒绝。 */
  @Test
  void rejectsSymlinkEscapeBeyondEnvironmentRoot() throws Exception {
    Path outside = Files.createTempDirectory("browser-outside");
    try {
      Files.createSymbolicLink(root.resolve("escape"), outside);
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> browser().list("escape"));
      assertTrue(error.getMessage().contains("escapes environment root"));
    } finally {
      deleteRecursively(outside);
    }
  }

  /** 指向 root 内部的 symlink 路径可浏览：响应回显请求的 wire 路径，内部只用 real path 校验与读取；条目仍不暴露 symlink 本身。 */
  @Test
  void allowsSymlinkAliasInsideRootAndEchoesRequestedWirePath() throws Exception {
    Files.createDirectories(root.resolve("real/child"));
    Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"));

    EnvironmentDirectoryListing listed = browser().list("alias");
    assertEquals("alias", listed.path());
    assertEquals("alias", listed.displayPath());
    assertEquals(".", listed.parentPath());
    assertEquals(
        List.of("alias/child"),
        listed.entries().stream().map(EnvironmentDirectoryEntry::path).toList());
    assertEquals(
        List.of("child"), listed.entries().stream().map(EnvironmentDirectoryEntry::name).toList());

    // 嵌套 alias 的父路径与条目路径同样使用请求的 wire 路径。
    Files.createDirectories(root.resolve("src"));
    Files.createSymbolicLink(root.resolve("src/alias"), root.resolve("real"));
    EnvironmentDirectoryListing nestedAlias = browser().list("src/alias");
    assertEquals("src/alias", nestedAlias.path());
    assertEquals("alias", nestedAlias.displayPath());
    assertEquals("src", nestedAlias.parentPath());
    assertEquals(
        List.of("src/alias/child"),
        nestedAlias.entries().stream().map(EnvironmentDirectoryEntry::path).toList());
  }

  /** 条目按名称稳定排序且最多 1000 条，超出置 truncated。 */
  @Test
  void sortsByNameAndTruncatesBeyondOneThousandEntries() throws Exception {
    for (int index = 0; index < 1005; index++) {
      Files.createDirectories(root.resolve("dir-" + String.format("%04d", index)));
    }
    Files.createDirectories(root.resolve("aaa"));

    EnvironmentDirectoryListing listed = browser().list(".");

    assertTrue(listed.truncated());
    assertEquals(EnvironmentDirectoryListing.MAX_ENTRIES, listed.entries().size());
    List<String> names = listed.entries().stream().map(EnvironmentDirectoryEntry::name).toList();
    assertEquals(names.stream().sorted().toList(), names);
    assertEquals("aaa", names.getFirst());
    assertEquals("dir-0000", names.get(1));
  }

  @Test
  void reportsNotFoundAndNotDirectory() throws Exception {
    Files.writeString(root.resolve("file.txt"), "x");
    assertThrows(NoSuchFileException.class, () -> browser().list("missing"));
    assertThrows(NoSuchFileException.class, () -> browser().list("missing/nested"));
    assertThrows(NotDirectoryException.class, () -> browser().list("file.txt"));
  }

  /** wire 形状契约在浏览器入口同样强制：absolute、反斜杠、空/点段、控制字符与越界路径拒绝。 */
  @Test
  void rejectsInvalidWirePathsBeforeIo() {
    for (String invalid :
        List.of("/abs", "a\\b", "a//b", "a/./b", "a/../b", "..", "a/", "a\u0000b")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> browser().list(invalid),
          "expected rejection for " + invalid);
    }
    Path outside = root.getParent().resolve("outside-" + System.nanoTime());
    IllegalArgumentException escaped =
        assertThrows(
            IllegalArgumentException.class, () -> browser().list("../" + outside.getFileName()));
    assertTrue(
        escaped.getMessage().contains("'..' segments") || escaped.getMessage().contains("escapes"));
  }

  /** 本地目录名若无法编码为合法 wire 子路径（Unix 上的 {@code C:}），该条目被跳过；截断按可编码条目计算，仍能填满 MAX_ENTRIES。 */
  @Test
  void skipsInvalidWireChildAndTruncatesFromRepresentableEntries() throws Exception {
    Path invalidChild = root.resolve("C:");
    try {
      Files.createDirectories(invalidChild);
    } catch (IOException error) {
      assumeTrue(false, "host cannot create a C: directory name: " + error.getMessage());
    }
    assumeTrue(Files.isDirectory(invalidChild), "host cannot retain a C: directory name");
    // C: 按名称排在 dir-0000 之前：若先按全部真实目录截断，会少返回一条合法条目。
    for (int index = 0; index < EnvironmentDirectoryListing.MAX_ENTRIES + 5; index++) {
      Files.createDirectories(root.resolve("dir-" + String.format("%04d", index)));
    }

    EnvironmentDirectoryListing listed = browser().list(".");

    assertTrue(listed.truncated());
    assertEquals(EnvironmentDirectoryListing.MAX_ENTRIES, listed.entries().size());
    List<String> names = listed.entries().stream().map(EnvironmentDirectoryEntry::name).toList();
    assertEquals(names.stream().sorted().toList(), names);
    assertFalse(names.contains("C:"));
    assertEquals("dir-0000", names.getFirst());
    assertEquals("dir-0999", names.getLast());
  }

  /** 可选 gitBranch：浏览目录或其祖先含 symbolic HEAD 时返回分支名，否则 null。 */
  @Test
  void attachesGitBranchFromSymbolicHeadOnly() throws Exception {
    Files.createDirectories(root.resolve("repo/.git"));
    Files.writeString(root.resolve("repo/.git/HEAD"), "ref: refs/heads/main\n");
    Files.createDirectories(root.resolve("repo/sub"));
    Files.createDirectories(root.resolve("no-git"));

    assertEquals("main", browser().list("repo").gitBranch());
    assertEquals("main", browser().list("repo/sub").gitBranch());
    assertNull(browser().list("no-git").gitBranch());

    Files.writeString(root.resolve("repo/.git/HEAD"), "not-a-ref");
    assertNull(browser().list("repo").gitBranch());
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 尽力清理临时 fixture
                }
              });
    }
  }
}
