package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Java NIO 搜索共享的无符号链接遍历、读取与 POSIX 路径规范化。 */
final class SearchFiles {

  private static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

  private SearchFiles() {}

  /**
   * 收集 {@code searchDirectory} 下的文件，规则基线取 {@code ignoreBase}；检索目标位于基线之外时以自身为基线。
   *
   * <p>遍历不跟随符号链接；{@code .git} 元数据目录在逐节点评估时被剪枝。
   */
  static List<Path> collect(Path ignoreBase, Path searchDirectory, SearchControl control)
      throws IOException, InterruptedException {
    requireReadableDirectory(searchDirectory);
    Path ruleBase = searchDirectory.startsWith(ignoreBase) ? ignoreBase : searchDirectory;
    GitIgnoreRules.Prepared prepared = GitIgnoreRules.prepare(ruleBase, searchDirectory, control);
    if (prepared.searchDirectoryIgnored()) {
      return List.of();
    }
    List<Path> files = new ArrayList<>();
    collectDirectory(searchDirectory, prepared.rules(), control, files);
    files.sort(Comparator.comparing(path -> toPosix(searchDirectory.relativize(path))));
    return List.copyOf(files);
  }

  static byte[] readAllBytes(Path file, SearchControl control)
      throws IOException, InterruptedException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      while (true) {
        control.check();
        int count = input.read(buffer);
        if (count < 0) {
          break;
        }
        output.write(buffer, 0, count);
      }
    }
    control.check();
    return output.toByteArray();
  }

  static String toPosix(Path path) {
    StringBuilder result = new StringBuilder();
    for (Path segment : path) {
      if (!result.isEmpty()) {
        result.append('/');
      }
      result.append(segment);
    }
    return result.toString();
  }

  static boolean isGitMetadata(Path path) {
    for (Path segment : path) {
      if (segment.toString().equals(".git")) {
        return true;
      }
    }
    return false;
  }

  private static void collectDirectory(
      Path directory, GitIgnoreRules rules, SearchControl control, List<Path> files)
      throws InterruptedException {
    control.check();
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        control.check();
        entries.add(entry);
      }
    } catch (IOException | SecurityException ignored) {
      return;
    }
    entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
    for (Path entry : entries) {
      control.check();
      BasicFileAttributes attributes;
      try {
        attributes =
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException ignored) {
        continue;
      }
      if (attributes.isSymbolicLink()) {
        continue;
      }
      if (attributes.isDirectory()) {
        if (!rules.isIgnored(entry, true, control)) {
          collectDirectory(entry, rules.withDirectoryRules(entry, control), control, files);
        }
      } else if (attributes.isRegularFile()
          && Files.isReadable(entry)
          && !rules.isIgnored(entry, false, control)) {
        files.add(entry);
      }
    }
  }

  private static void requireReadableDirectory(Path directory) {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("path must be a directory");
    }
    if (!Files.isReadable(directory)) {
      throw new IllegalArgumentException("path is not readable: " + directory);
    }
  }
}
