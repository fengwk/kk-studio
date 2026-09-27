package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.Map;

/**
 * {@link TextFileCommit} 的属性边界与失败语义测试。
 *
 * <p>覆盖两条 capability 用例无法触达的路径：不支持 POSIX 权限视图的文件系统（以 JDK 自带 zip 文件系统模拟， 对应 Windows 等平台的“不假设、不模拟
 * POSIX”分支），以及目标不是普通文件/悬空符号链接时必须在提交前拒绝。
 *
 * <p>平台边界：JDK 自带 zip 文件系统实际也支持原子改名，因此这里无法构造「平台不支持原子改名」的确定性用例；该分支在实现中不做 非原子降级，也只在开发机原生文件系统上验证过，未在
 * Windows 上运行。
 */
class TextFileCommitTest {

  @TempDir Path workdir;

  /**
   * 无 POSIX 权限视图的文件系统上必须跳过权限继承，同时仍然完成原子替换并写入完整内容。
   *
   * <p>这条用例在 Linux 上也能执行“非 POSIX 平台”分支，避免平台专属语义只能靠跳过声明。
   */
  @Test
  void commitsOnFileSystemWithoutPosixPermissionView() throws Exception {
    Path archive = workdir.resolve("store.zip");
    try (FileSystem zip =
        FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      Path directory = zip.getPath("/sub");
      Files.createDirectories(directory);
      Path target = directory.resolve("target.txt");
      Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
      assertTrue(
          !Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class),
          "zip 文件系统不应支持 POSIX 权限视图，否则该用例失去意义");

      TextFileCommit.commit(target, "new".getBytes(StandardCharsets.UTF_8));

      assertEquals("new", Files.readString(target));
    }
  }

  /** 悬空符号链接不是待创建的新文件：提交必须失败并原样保留链接。 */
  @Test
  void rejectsSymbolicLinkTargetBeforeCommit() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 符号链接语义");
    Path link = workdir.resolve("dangling.txt");
    Files.createSymbolicLink(link, workdir.resolve("absent.txt"));

    IOException error =
        assertThrows(
            IOException.class,
            () -> TextFileCommit.commit(link, "x".getBytes(StandardCharsets.UTF_8)));

    assertTrue(error.getMessage().contains("symbolic link"), error.getMessage());
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(!Files.exists(workdir.resolve("absent.txt")));
  }

  /** 目录等非普通文件在提交前拒绝，目录内容保持不变。 */
  @Test
  void rejectsNonRegularTargetBeforeCommit() throws Exception {
    Path directory = workdir.resolve("nested");
    Files.createDirectory(directory);
    Files.writeString(directory.resolve("kept.txt"), "kept\n");

    IOException error =
        assertThrows(
            IOException.class,
            () -> TextFileCommit.commit(directory, "x".getBytes(StandardCharsets.UTF_8)));

    assertTrue(error.getMessage().contains("not a regular file"), error.getMessage());
    assertEquals("kept\n", Files.readString(directory.resolve("kept.txt")));
  }

  /** 提交成功时不留下 staging 文件，并保留既有文件字节级替换语义。 */
  @Test
  void replacesTargetContentAndLeavesNoStagingFile() throws Exception {
    Path target = workdir.resolve("target.txt");
    byte[] original = "old\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target, original);
    byte[] replacement = "new\n".getBytes(StandardCharsets.UTF_8);

    TextFileCommit.commit(target, replacement);

    assertArrayEquals(replacement, Files.readAllBytes(target));
    try (var entries = Files.list(workdir)) {
      assertEquals(
          List.of("target.txt"),
          entries.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  private static boolean posixSupported(Path path) throws IOException {
    FileStore store = Files.getFileStore(path);
    return store.supportsFileAttributeView(PosixFileAttributeView.class);
  }
}
