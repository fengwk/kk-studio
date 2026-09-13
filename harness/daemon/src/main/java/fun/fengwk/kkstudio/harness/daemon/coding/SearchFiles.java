package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Java NIO 搜索共享的无符号链接遍历、早期终止控制与 POSIX 路径规范化。 */
final class SearchFiles {

  @FunctionalInterface
  interface FileConsumer {
    /** 返回 false 表示终止遍历。 */
    boolean accept(Path file) throws Exception;
  }

  private SearchFiles() {}

  /**
   * 使用 early-stop 访问器遍历 {@code searchDirectory} 下的文件，规则基线取 {@code ignoreBase}。
   *
   * <p>遍历不跟随符号链接；{@code .git} 元数据目录在逐节点评估时被剪枝。
   */
  static void walk(
      Path ignoreBase, Path searchDirectory, SearchControl control, FileConsumer consumer)
      throws Exception {
    requireReadableDirectory(searchDirectory);
    Path ruleBase = searchDirectory.startsWith(ignoreBase) ? ignoreBase : searchDirectory;
    GitIgnoreRules.Prepared prepared = GitIgnoreRules.prepare(ruleBase, searchDirectory, control);
    if (prepared.searchDirectoryIgnored()) {
      return;
    }
    walkDirectory(searchDirectory, prepared.rules(), control, consumer);
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

  private static boolean walkDirectory(
      Path directory, GitIgnoreRules rules, SearchControl control, FileConsumer consumer)
      throws Exception {
    control.check();
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        control.check();
        entries.add(entry);
      }
    } catch (IOException | SecurityException ignored) {
      return true;
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
          boolean continueWalk =
              walkDirectory(entry, rules.withDirectoryRules(entry, control), control, consumer);
          if (!continueWalk) {
            return false;
          }
        }
      } else if (attributes.isRegularFile()
          && Files.isReadable(entry)
          && !rules.isIgnored(entry, false, control)) {
        boolean continueWalk = consumer.accept(entry);
        if (!continueWalk) {
          return false;
        }
      }
    }
    return true;
  }

  private static void requireReadableDirectory(Path directory) {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("path must be a directory");
    }
    if (!Files.isReadable(directory)) {
      throw new IllegalArgumentException("path is not readable: " + directory);
    }
  }
}
