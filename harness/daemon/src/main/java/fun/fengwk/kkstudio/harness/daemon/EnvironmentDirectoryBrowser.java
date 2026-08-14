package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Environment Root 下的类型化单层目录浏览。
 *
 * <p>control-plane 只读操作，不经 Tool Invocation/Permission，不占 active tool slot。每次只列一层、只返回真实目录、按名称稳定排序、最多
 * {@link DaemonDirectoryCodec#MAX_ENTRIES} 条；默认不暴露 symlink 目录。请求 {@code path} 必须是 Environment Root
 * 下的 canonical 相对 wire 路径（{@code '.'} 表示 root），最终在 daemon 侧 canonicalize：越出 root 的 symlink 穿越被拒绝。
 *
 * <p>失败分类：非法路径/越界 → {@link IllegalArgumentException}；不存在 → {@link NoSuchFileException}；非目录 → {@link
 * NotDirectoryException}；其余本地 IO 失败 → {@link IOException}。
 */
public final class EnvironmentDirectoryBrowser {

  private final Path environmentRoot;

  public EnvironmentDirectoryBrowser(Path environmentRoot) {
    Path root = Objects.requireNonNull(environmentRoot, "environmentRoot");
    try {
      this.environmentRoot = root.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException("environmentRoot must be an existing directory", error);
    }
    if (!Files.isDirectory(this.environmentRoot)) {
      throw new IllegalArgumentException("environmentRoot must be an existing directory");
    }
  }

  /** 浏览 {@code path}（{@code '.'} 表示 root）的单层目录列表。 */
  public DaemonDirectoryCodec.DirectoryListed list(String path) throws IOException {
    DaemonDirectoryCodec.requireCanonicalRelativePath(path);
    Path directory = canonicalDirectory(path);
    List<Path> children = listChildren(directory);
    boolean truncated = children.size() > DaemonDirectoryCodec.MAX_ENTRIES;
    List<DaemonDirectoryCodec.DirectoryEntry> entries = new ArrayList<>();
    int count = Math.min(DaemonDirectoryCodec.MAX_ENTRIES, children.size());
    for (int index = 0; index < count; index++) {
      Path child = children.get(index);
      entries.add(
          new DaemonDirectoryCodec.DirectoryEntry(
              toPosix(environmentRoot.relativize(child)), child.getFileName().toString()));
    }
    String relativePath = toPosix(environmentRoot.relativize(directory));
    String displayPath = ".".equals(relativePath) ? "." : lastSegment(relativePath);
    String parentPath =
        ".".equals(relativePath) ? "." : toPosix(environmentRoot.relativize(directory.getParent()));
    return new DaemonDirectoryCodec.DirectoryListed(
        relativePath, displayPath, parentPath, truncated, gitBranch(directory), entries);
  }

  private Path canonicalDirectory(String path) throws IOException {
    Path candidate = environmentRoot.resolve(Path.of(path)).normalize();
    Path canonical;
    try {
      canonical = candidate.toRealPath();
    } catch (IOException error) {
      if (error instanceof NoSuchFileException) {
        throw (NoSuchFileException) error;
      }
      throw new IOException("cannot resolve path: " + path, error);
    }
    if (!canonical.startsWith(environmentRoot)) {
      throw new IllegalArgumentException("path escapes environment root: " + path);
    }
    if (!Files.isDirectory(canonical)) {
      throw new NotDirectoryException(canonical.toString());
    }
    return canonical;
  }

  private List<Path> listChildren(Path directory) throws IOException {
    List<Path> children = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path child : stream) {
        if (Files.isSymbolicLink(child)) {
          continue;
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        children.add(child);
      }
    }
    children.sort(Comparator.comparing(path -> path.getFileName().toString()));
    return children;
  }

  /** 从浏览目录向上到 root 查找 {@code .git}；仅 symbolic HEAD（{@code ref: refs/heads/...}）返回分支名，其余返回 null。 */
  private String gitBranch(Path directory) {
    Path current = directory;
    while (current != null && current.startsWith(environmentRoot)) {
      Path gitDir = current.resolve(".git");
      if (Files.isDirectory(gitDir, LinkOption.NOFOLLOW_LINKS)) {
        return readBranch(gitDir);
      }
      if (current.equals(environmentRoot)) {
        break;
      }
      current = current.getParent();
    }
    return null;
  }

  private static String readBranch(Path gitDir) {
    try {
      Path head = gitDir.resolve("HEAD");
      if (!Files.isRegularFile(head)) {
        return null;
      }
      String content = Files.readString(head, StandardCharsets.US_ASCII).strip();
      String prefix = "ref: refs/heads/";
      if (!content.startsWith(prefix)) {
        return null;
      }
      String branch = content.substring(prefix.length());
      return branch.isEmpty() ? null : branch;
    } catch (IOException error) {
      return null;
    }
  }

  private static String toPosix(Path path) {
    StringBuilder result = new StringBuilder();
    for (Path segment : path) {
      if (!result.isEmpty()) {
        result.append('/');
      }
      result.append(segment);
    }
    return result.length() == 0 ? "." : result.toString();
  }

  private static String lastSegment(String relativePath) {
    int separator = relativePath.lastIndexOf('/');
    return separator < 0 ? relativePath : relativePath.substring(separator + 1);
  }
}
