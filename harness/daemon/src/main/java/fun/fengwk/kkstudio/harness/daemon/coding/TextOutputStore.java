package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地大文本输出的受控临时边界：把每次外化落进一个登记过的临时 workspace，中转文件再同一目录内原子发布为 durable 全文。
 *
 * <p>每次 {@link #createStagingFile(String)} 都登记一个新的 workspace（{@code <tmp>/workspaces/<uuid>}），中转文件
 * {@code *.part} 与发布后的全文 {@code *.log} 都位于该 workspace 内；workspace 的不可变 {@code createdAt} 登记与 in-use
 * lease 由 {@link TemporaryResourceStore} 维护，{@link #sweep(long)} 只回收超过保留期且未在用的 workspace。
 *
 * <p>发布后的绝对路径成为模型历史可能继续引用的事实；它不会被任何组件隐式删除，只会在超过当前保留期且不再被使用时由定时清扫回收。
 * 写入失败只降级为有界预览，绝不谎报成功，也绝不把失败改成整段大文本内联回传。
 */
public final class TextOutputStore {

  /** 默认单次调用的全文捕获预算：1 GiB。达到预算只停止文件捕获，绝不终止产生输出的进程。 */
  public static final long DEFAULT_CAPTURE_BUDGET_BYTES = 1024L * 1024 * 1024;

  private static final String STAGING_SUFFIX = ".part";
  private static final String TEXT_SUFFIX = ".log";
  private static final int MAX_NAME_CHARS = 64;

  private final TemporaryResourceStore temporaryResources;
  private final long captureBudgetBytes;

  /** 未发布中转文件 -> 其所属登记 workspace；发布或清理时据此释放 in-use lease。 */
  private final ConcurrentHashMap<Path, TemporaryResourceStore.Workspace> openWorkspaces =
      new ConcurrentHashMap<>();

  private TextOutputStore(TemporaryResourceStore temporaryResources, long captureBudgetBytes) {
    this.temporaryResources = temporaryResources;
    this.captureBudgetBytes = captureBudgetBytes;
  }

  /** 在受控临时根下打开（必要时创建）workspace 根，使用默认捕获预算。 */
  public static TextOutputStore open(Path tmpRoot) {
    return open(tmpRoot, DEFAULT_CAPTURE_BUDGET_BYTES);
  }

  /**
   * 在受控临时根下打开（必要时创建）workspace 根。
   *
   * @param captureBudgetBytes 单次调用的全文捕获预算，必须为正
   */
  public static TextOutputStore open(Path tmpRoot, long captureBudgetBytes) {
    if (captureBudgetBytes <= 0) {
      throw new IllegalArgumentException("captureBudgetBytes must be positive");
    }
    return new TextOutputStore(TemporaryResourceStore.open(tmpRoot), captureBudgetBytes);
  }

  /** 受控临时 workspace 根目录（{@code <tmp>/workspaces}）。 */
  public Path root() {
    return temporaryResources.root();
  }

  /** 单次调用的全文捕获预算。 */
  public long captureBudgetBytes() {
    return captureBudgetBytes;
  }

  /** 按当前保留期清扫已登记、超期且未在用的临时 workspace，返回删除数量。 */
  public int sweep(long ttlSeconds) {
    return temporaryResources.sweep(ttlSeconds);
  }

  /**
   * 若 {@code candidate} 位于某个已登记受控 workspace 内，则获取其 in-use lease 并返回；否则返回 {@link Lease#NONE}。
   *
   * <p>读取受控临时全文（例如 {@code read}）期间必须持有该 lease，保证定时清扫不会删除在途读取的 workspace。
   */
  public Lease acquire(Path candidate) {
    Objects.requireNonNull(candidate, "candidate");
    return temporaryResources.acquire(candidate);
  }

  /** 受控临时产物的 in-use 租约：读取期间持有，{@link #close()} 释放且不抛受检异常。 */
  public interface Lease extends AutoCloseable {

    @Override
    void close();

    /** 非受控路径的 no-op 租约。 */
    Lease NONE = () -> {};
  }

  /** 所有已发布全文（{@code workspaces} 下的 {@code *.log}）；仅供测试与诊断。 */
  List<Path> publishedFiles() {
    return listFilesWithSuffix(TEXT_SUFFIX);
  }

  /** 所有未发布中转文件（{@code workspaces} 下的 {@code *.part}）；仅供测试与诊断。 */
  List<Path> partialFiles() {
    return listFilesWithSuffix(STAGING_SUFFIX);
  }

  private List<Path> listFilesWithSuffix(String suffix) {
    List<Path> result = new ArrayList<>();
    try (DirectoryStream<Path> workspaces = Files.newDirectoryStream(root())) {
      for (Path workspace : workspaces) {
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(workspace)) {
          for (Path file : files) {
            if (file.getFileName().toString().endsWith(suffix)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
              result.add(file);
            }
          }
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return result;
  }

  /**
   * 登记新 workspace 并在其中创建 owner-only 中转文件：随机唯一后缀，0400/0600 语义，拒绝符号链接。
   *
   * @param callId 调用标识，只用于生成可读前缀
   */
  public Path createStagingFile(String callId) throws IOException {
    TemporaryResourceStore.Workspace workspace = temporaryResources.create();
    boolean reusable = false;
    try {
      String name = uniqueName(callId);
      Path stagingFile = workspace.directory().resolve(name);
      OwnerOnlyFiles.createOwnerOnlyFile(temporaryResources.trustedRoot(), stagingFile);
      openWorkspaces.put(stagingFile, workspace);
      reusable = true;
      return stagingFile;
    } finally {
      if (!reusable) {
        workspace.close();
        temporaryResources.deleteWorkspace(workspace.directory());
      }
    }
  }

  /**
   * 把中转文件原子发布为 durable 全文，返回其绝对路径。
   *
   * <p>发布只做一次 move；目标名继承中转文件的唯一后缀，因此并发调用不会互相覆盖。发布完成后释放该 workspace 的 in-use
   * lease，但保留目录与全文，直到超过保留期被清扫。
   */
  public Path publish(Path stagingFile) throws IOException {
    Objects.requireNonNull(stagingFile, "stagingFile");
    String stagingName = stagingFile.getFileName().toString();
    if (!stagingName.endsWith(STAGING_SUFFIX)) {
      throw new IllegalArgumentException("staging file must end with " + STAGING_SUFFIX);
    }
    String textName =
        stagingName.substring(0, stagingName.length() - STAGING_SUFFIX.length()) + TEXT_SUFFIX;
    Path target = stagingFile.resolveSibling(textName);
    Path published;
    try {
      published = Files.move(stagingFile, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException error) {
      published = Files.move(stagingFile, target);
    }
    releaseWorkspace(stagingFile);
    return published;
  }

  /** 清理未发布的中转文件并释放其 workspace；已发布全文不受影响。 */
  void deleteStagingQuietly(Path stagingFile) {
    if (stagingFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(stagingFile);
    } catch (IOException ignored) {
      // 残留中转文件由后续清扫收敛。
    }
    TemporaryResourceStore.Workspace workspace = openWorkspaces.remove(stagingFile);
    if (workspace != null) {
      workspace.close();
      temporaryResources.deleteWorkspace(workspace.directory());
    }
  }

  private void releaseWorkspace(Path stagingFile) {
    TemporaryResourceStore.Workspace workspace = openWorkspaces.remove(stagingFile);
    if (workspace != null) {
      workspace.close();
    }
  }

  /** 生成 workspace 内唯一文件名：可读前缀 + 随机后缀，避免并发调用互相覆盖。 */
  private static String uniqueName(String callId) {
    return sanitize(callId) + "-" + UUID.randomUUID().toString().substring(0, 8) + STAGING_SUFFIX;
  }

  /** 把调用标识收敛为文件名安全前缀，避免路径穿越与展示歧义。 */
  private static String sanitize(String callId) {
    String value = callId == null ? "" : callId;
    StringBuilder builder = new StringBuilder(Math.min(value.length(), MAX_NAME_CHARS));
    for (int index = 0; index < value.length() && builder.length() < MAX_NAME_CHARS; index++) {
      char character = value.charAt(index);
      boolean allowed =
          (character >= 'a' && character <= 'z')
              || (character >= 'A' && character <= 'Z')
              || (character >= '0' && character <= '9')
              || character == '-'
              || character == '_'
              || character == '.';
      builder.append(allowed ? character : '_');
    }
    return builder.isEmpty() ? "output" : builder.toString();
  }
}
