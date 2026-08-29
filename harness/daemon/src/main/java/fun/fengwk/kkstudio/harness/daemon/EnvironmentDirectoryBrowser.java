package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;

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
 * {@link EnvironmentDirectoryListing#MAX_ENTRIES} 条；列表默认不暴露 symlink 目录。请求 {@code path} 必须是
 * Environment Root 下的 canonical 相对 wire 路径（{@code '.'} 表示 root），最终在 daemon 侧 canonicalize：越出 root 的
 * symlink 穿越被拒绝。
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
  public EnvironmentDirectoryListing list(String path) throws IOException {
    EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    Path canonical = canonicalDirectory(path);
    List<EnvironmentDirectoryEntry> representable = listRepresentableEntries(canonical, path);
    boolean truncated = representable.size() > EnvironmentDirectoryListing.MAX_ENTRIES;
    List<EnvironmentDirectoryEntry> entries =
        List.copyOf(
            representable.subList(
                0, Math.min(EnvironmentDirectoryListing.MAX_ENTRIES, representable.size())));
    return new EnvironmentDirectoryListing(
        path, displayPath(path), parentWirePath(path), truncated, gitBranch(canonical), entries);
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

  /** 先收集可编码为 wire 的直属子目录，再按名称稳定排序；截断基于该集合，避免非法本地名挤掉后续合法条目。 */
  private List<EnvironmentDirectoryEntry> listRepresentableEntries(
      Path directory, String requestPath) throws IOException {
    List<EnvironmentDirectoryEntry> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path child : stream) {
        if (Files.isSymbolicLink(child)) {
          continue;
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        String name = child.getFileName().toString();
        if (!isWireRepresentableName(name)) {
          continue;
        }
        String childPath = childWirePath(requestPath, name);
        if (!isWireRepresentableChild(childPath, name)) {
          continue;
        }
        entries.add(new EnvironmentDirectoryEntry(name, childPath));
      }
    }
    entries.sort(Comparator.comparing(EnvironmentDirectoryEntry::name));
    return entries;
  }

  /** 目录名必须能作为 wire 段：非空白、无 '/'、无反斜杠、无 ISO 控制字符。 */
  private static boolean isWireRepresentableName(String name) {
    return !name.isBlank()
        && name.indexOf('/') < 0
        && name.indexOf('\\') < 0
        && name.codePoints().noneMatch(Character::isISOControl);
  }

  /** 生成的子路径必须通过共享 {@link EnvironmentWorkspacePath} 校验，且 name 仍是其最后一段；否则跳过该条目。 */
  private static boolean isWireRepresentableChild(String childPath, String name) {
    try {
      EnvironmentWorkspacePath.requireCanonicalRelativePath(childPath);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
    int separator = childPath.lastIndexOf('/');
    String lastSegment = separator < 0 ? childPath : childPath.substring(separator + 1);
    return name.equals(lastSegment);
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

  /** entry 的 canonical wire 路径：请求目录的直接子路径（root 请求为单段）。 */
  private static String childWirePath(String parentPath, String name) {
    return ".".equals(parentPath) ? name : parentPath + "/" + name;
  }

  /** {@code displayPath} 是请求 wire path 的最后一段（root 为 {@code '.'}），绝不暴露本地绝对路径。 */
  private static String displayPath(String path) {
    int separator = path.lastIndexOf('/');
    return separator < 0 ? path : path.substring(separator + 1);
  }

  /** 请求目录父目录的 canonical wire 路径（root 为 {@code '.'}）。 */
  private static String parentWirePath(String path) {
    int separator = path.lastIndexOf('/');
    return separator < 0 ? "." : path.substring(0, separator);
  }
}
