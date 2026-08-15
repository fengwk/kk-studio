package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.EnvironmentWorkspacePath;
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
 * {@link DaemonDirectoryCodec#MAX_ENTRIES} 条；列表默认不暴露 symlink 目录。请求 {@code path} 必须是 Environment
 * Root 下的 canonical 相对 wire 路径（{@code '.'} 表示 root），最终在 daemon 侧 canonicalize：越出 root 的 symlink
 * 穿越被拒绝。
 *
 * <p>成功响应中 {@code path}/{@code parentPath}/entry {@code path} 一律使用请求的 canonical wire 路径（显式请求 root 内
 * symlink alias 时也回显 alias 本身，内部只用 real path 校验与读取，绝不越过 root）；{@code parentPath} 是请求 {@code path} 的
 * lexical 父路径（root 与单段路径均为 {@code '.'}）；{@code displayPath} 是请求 {@code path} 的最后一段（root 为 {@code
 * '.'}，symlink alias 请求回显 alias 段），只作展示、绝不暴露 daemon 本地绝对路径。本地子目录名若无法编码为合法 wire 子路径（例如 Unix 上的
 * {@code C:}），该条目被跳过，不使整次列表失败。
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

  /** 浏览 {@code requestId}/{@code path}（{@code '.'} 表示 root）的单层目录列表；{@code requestId} 是请求回显。 */
  public DaemonDirectoryCodec.DirectoryListed list(String requestId, String path)
      throws IOException {
    DaemonDirectoryCodec.requireCanonicalRelativePath(path);
    Path canonical = canonicalDirectory(path);
    List<Path> children = listChildren(canonical);
    boolean truncated = children.size() > DaemonDirectoryCodec.MAX_ENTRIES;
    List<DaemonDirectoryCodec.DirectoryEntry> entries = new ArrayList<>();
    int count = Math.min(DaemonDirectoryCodec.MAX_ENTRIES, children.size());
    for (int index = 0; index < count; index++) {
      Path child = children.get(index);
      String name = child.getFileName().toString();
      String childPath = childWirePath(path, name);
      if (!isWireRepresentableChild(childPath, name)) {
        continue;
      }
      entries.add(new DaemonDirectoryCodec.DirectoryEntry(name, childPath));
    }
    return new DaemonDirectoryCodec.DirectoryListed(
        requestId,
        path,
        displayPath(path),
        parentWirePath(path),
        truncated,
        gitBranch(canonical),
        entries);
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
        if (!isWireRepresentableName(child.getFileName().toString())) {
          continue;
        }
        children.add(child);
      }
    }
    children.sort(Comparator.comparing(path -> path.getFileName().toString()));
    return children;
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
