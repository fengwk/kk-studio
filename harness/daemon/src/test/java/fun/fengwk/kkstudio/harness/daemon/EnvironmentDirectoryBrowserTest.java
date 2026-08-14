package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Environment Root 单层目录浏览的路径边界、排序、上限与 git 分支契约。 */
class EnvironmentDirectoryBrowserTest {

  private static final String REQUEST_ID = "3f0c6b2e-8d1a-4f5e-9c2b-1a2b3c4d5e6f";

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

    DaemonDirectoryCodec.DirectoryListed listed = browser().list(REQUEST_ID, ".");

    assertEquals(".", listed.path());
    assertEquals(root.toRealPath().toString(), listed.displayPath());
    assertEquals(".", listed.parentPath());
    assertFalse(listed.truncated());
    assertNull(listed.gitBranch());
    assertEquals(
        List.of("docs", "src"),
        listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::name).toList());
    assertEquals(
        List.of("docs", "src"),
        listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::path).toList());

    DaemonDirectoryCodec.DirectoryListed nested = browser().list(REQUEST_ID, "src");
    assertEquals("src", nested.path());
    assertEquals(root.toRealPath().resolve("src").toString(), nested.displayPath());
    assertEquals(".", nested.parentPath());
    // 条目 path 是请求目录的直接子路径（wire），name 是目录名。
    assertEquals(
        List.of("src/main", "src/test"),
        nested.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::path).toList());
    assertEquals(
        List.of("main", "test"),
        nested.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::name).toList());

    DaemonDirectoryCodec.DirectoryListed deep = browser().list(REQUEST_ID, "src/main");
    assertEquals("src/main", deep.path());
    assertEquals(root.toRealPath().resolve("src/main").toString(), deep.displayPath());
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

      DaemonDirectoryCodec.DirectoryListed listed = browser().list(REQUEST_ID, ".");
      assertEquals(
          List.of("real"),
          listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::path).toList());
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
          assertThrows(IllegalArgumentException.class, () -> browser().list(REQUEST_ID, "escape"));
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

    DaemonDirectoryCodec.DirectoryListed listed = browser().list(REQUEST_ID, "alias");
    assertEquals("alias", listed.path());
    assertEquals(root.toRealPath().resolve("real").toString(), listed.displayPath());
    assertEquals(".", listed.parentPath());
    assertEquals(
        List.of("alias/child"),
        listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::path).toList());
    assertEquals(
        List.of("child"),
        listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::name).toList());

    // 嵌套 alias 的父路径与条目路径同样使用请求的 wire 路径。
    Files.createDirectories(root.resolve("src"));
    Files.createSymbolicLink(root.resolve("src/alias"), root.resolve("real"));
    DaemonDirectoryCodec.DirectoryListed nestedAlias = browser().list(REQUEST_ID, "src/alias");
    assertEquals("src/alias", nestedAlias.path());
    assertEquals("src", nestedAlias.parentPath());
    assertEquals(
        List.of("src/alias/child"),
        nestedAlias.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::path).toList());
  }

  /** 条目按名称稳定排序且最多 1000 条，超出置 truncated。 */
  @Test
  void sortsByNameAndTruncatesBeyondOneThousandEntries() throws Exception {
    for (int index = 0; index < 1005; index++) {
      Files.createDirectories(root.resolve("dir-" + String.format("%04d", index)));
    }
    Files.createDirectories(root.resolve("aaa"));

    DaemonDirectoryCodec.DirectoryListed listed = browser().list(REQUEST_ID, ".");

    assertTrue(listed.truncated());
    assertEquals(DaemonDirectoryCodec.MAX_ENTRIES, listed.entries().size());
    List<String> names =
        listed.entries().stream().map(DaemonDirectoryCodec.DirectoryEntry::name).toList();
    assertEquals(names.stream().sorted().toList(), names);
    assertEquals("aaa", names.getFirst());
    assertEquals("dir-0000", names.get(1));
  }

  @Test
  void reportsNotFoundAndNotDirectory() throws Exception {
    Files.writeString(root.resolve("file.txt"), "x");
    assertThrows(NoSuchFileException.class, () -> browser().list(REQUEST_ID, "missing"));
    assertThrows(NoSuchFileException.class, () -> browser().list(REQUEST_ID, "missing/nested"));
    assertThrows(NotDirectoryException.class, () -> browser().list(REQUEST_ID, "file.txt"));
  }

  /** wire 形状契约在浏览器入口同样强制：absolute、反斜杠、空/点段、控制字符与越界路径拒绝。 */
  @Test
  void rejectsInvalidWirePathsBeforeIo() {
    for (String invalid :
        List.of("/abs", "a\\b", "a//b", "a/./b", "a/../b", "..", "a/", "a\u0000b")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> browser().list(REQUEST_ID, invalid),
          "expected rejection for " + invalid);
    }
    Path outside = root.getParent().resolve("outside-" + System.nanoTime());
    IllegalArgumentException escaped =
        assertThrows(
            IllegalArgumentException.class,
            () -> browser().list(REQUEST_ID, "../" + outside.getFileName()));
    assertTrue(
        escaped.getMessage().contains("'..' segments") || escaped.getMessage().contains("escapes"));
  }

  /** 可选 gitBranch：浏览目录或其祖先含 symbolic HEAD 时返回分支名，否则 null。 */
  @Test
  void attachesGitBranchFromSymbolicHeadOnly() throws Exception {
    Files.createDirectories(root.resolve("repo/.git"));
    Files.writeString(root.resolve("repo/.git/HEAD"), "ref: refs/heads/main\n");
    Files.createDirectories(root.resolve("repo/sub"));
    Files.createDirectories(root.resolve("no-git"));

    assertEquals("main", browser().list(REQUEST_ID, "repo").gitBranch());
    assertEquals("main", browser().list(REQUEST_ID, "repo/sub").gitBranch());
    assertNull(browser().list(REQUEST_ID, "no-git").gitBranch());

    Files.writeString(root.resolve("repo/.git/HEAD"), "not-a-ref");
    assertNull(browser().list(REQUEST_ID, "repo").gitBranch());
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
