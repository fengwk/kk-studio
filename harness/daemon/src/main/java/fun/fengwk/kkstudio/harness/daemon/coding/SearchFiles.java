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

/**
 * Java NIO 搜索共享的无符号链接遍历、早期终止控制与 POSIX 路径规范化。
 *
 * <p>忽略规则从检索起点自身的祖先链解析（见 {@link GitIgnoreRules}），不依赖调用方 workdir，因此同一目标的匹配只由目标路径决定。
 */
final class SearchFiles {

  @FunctionalInterface
  interface FileConsumer {
    /** 返回 false 表示终止遍历。 */
    boolean accept(Path file) throws Exception;
  }

  /**
   * 一次遍历的副作用报告。
   *
   * @param unreadable 无法读取的目录或普通文件；调用方必须显式呈现，不能默默等同于“没有内容”。
   */
  record Report(List<Path> unreadable) {}

  private SearchFiles() {}

  /**
   * 使用 early-stop 访问器遍历 {@code searchDirectory} 下的文件。
   *
   * <p>遍历不跟随符号链接；{@code .git} 元数据目录在逐节点评估时被剪枝；检索起点自身被祖先规则忽略时不访问任何文件。无法读取的节点记入 {@link
   * Report#unreadable()}，由调用方决定如何上报。
   */
  static Report walk(Path searchDirectory, SearchControl control, FileConsumer consumer)
      throws Exception {
    requireReadableDirectory(searchDirectory);
    Path target = searchDirectory.toAbsolutePath().normalize();
    GitIgnoreRules.Prepared prepared = GitIgnoreRules.prepare(target, control);
    if (prepared.searchDirectoryIgnored()) {
      return new Report(List.of());
    }
    List<Path> unreadable = new ArrayList<>();
    walkDirectory(target, prepared.rules(), control, consumer, unreadable);
    return new Report(List.copyOf(unreadable));
  }

  /**
   * 转成 '/' 分隔的展示路径。
   *
   * <p>用平台 {@code toString()} 而不是逐段拼接：逐段拼接会丢掉根前缀，把绝对路径渲染成看起来像相对路径的字符串，展示结果便不再是可再次使用的真实路径。
   */
  static String toPosix(Path path) {
    return path.toString().replace('\\', '/');
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
      Path directory,
      GitIgnoreRules rules,
      SearchControl control,
      FileConsumer consumer,
      List<Path> unreadable)
      throws Exception {
    control.check();
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        control.check();
        entries.add(entry);
      }
    } catch (IOException | SecurityException error) {
      unreadable.add(directory);
      return true;
    }
    entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
    for (Path entry : entries) {
      control.check();
      BasicFileAttributes attributes;
      try {
        attributes =
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException error) {
        unreadable.add(entry);
        continue;
      }
      if (attributes.isSymbolicLink()) {
        continue;
      }
      if (attributes.isDirectory()) {
        if (!rules.isIgnored(entry, true, control)) {
          boolean continueWalk =
              walkDirectory(
                  entry, rules.withDirectoryRules(entry, control), control, consumer, unreadable);
          if (!continueWalk) {
            return false;
          }
        }
      } else if (attributes.isRegularFile()) {
        if (!Files.isReadable(entry)) {
          unreadable.add(entry);
          continue;
        }
        if (!rules.isIgnored(entry, false, control)) {
          boolean continueWalk = consumer.accept(entry);
          if (!continueWalk) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static void requireReadableDirectory(Path directory) {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new ToolInputRejectedException("path must be a directory");
    }
    if (!Files.isReadable(directory)) {
      throw new ToolInputRejectedException("path is not readable: " + directory);
    }
  }
}
