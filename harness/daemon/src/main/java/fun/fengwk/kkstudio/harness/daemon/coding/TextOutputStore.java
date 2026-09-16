package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 本地大文本输出的持久化边界：owner-only 中转文件加原子发布。
 *
 * <p>写入先落在 {@code <resources>/staging/*.part}（0600、非符号链接），完成后以原子 move 发布为 {@code
 * <text>/<invocation>-<unique>.log}。发布完成即成为 durable 事实：模型历史可能仍引用该绝对路径，因此本组件只提供写入与读取位置，不提供任何隐式删除；
 * 只有未发布的中转文件会在失败/关闭路径上被清理。
 *
 * <p>文本不是 {@code Resource}：不经过 blob 导出根、不做内容寻址、没有大小上界，也不参与 Resource 元数据校验。
 */
public final class TextOutputStore {

  /** 默认单次调用的全文捕获预算：1 GiB。达到预算只停止文件捕获，绝不终止产生输出的进程。 */
  public static final long DEFAULT_CAPTURE_BUDGET_BYTES = 1024L * 1024 * 1024;

  private static final String STAGING_SUFFIX = ".part";
  private static final String TEXT_SUFFIX = ".log";
  private static final int MAX_NAME_CHARS = 64;

  private final Path textDirectory;
  private final Path stagingDirectory;
  private final long captureBudgetBytes;

  private TextOutputStore(Path textDirectory, Path stagingDirectory, long captureBudgetBytes) {
    this.textDirectory = textDirectory;
    this.stagingDirectory = stagingDirectory;
    this.captureBudgetBytes = captureBudgetBytes;
  }

  /** 打开（必要时创建）文本与中转目录，使用默认捕获预算。 */
  public static TextOutputStore open(Path textDirectory, Path stagingDirectory) {
    return open(textDirectory, stagingDirectory, DEFAULT_CAPTURE_BUDGET_BYTES);
  }

  /**
   * 打开（必要时创建）文本与中转目录。
   *
   * @param captureBudgetBytes 单次调用的全文捕获预算，必须为正
   */
  public static TextOutputStore open(
      Path textDirectory, Path stagingDirectory, long captureBudgetBytes) {
    if (captureBudgetBytes <= 0) {
      throw new IllegalArgumentException("captureBudgetBytes must be positive");
    }
    Path text = prepareDirectory(textDirectory, "textDirectory");
    Path staging = prepareDirectory(stagingDirectory, "stagingDirectory");
    return new TextOutputStore(text, staging, captureBudgetBytes);
  }

  /** 已发布全文所在目录。 */
  public Path textDirectory() {
    return textDirectory;
  }

  /** 未发布中转文件所在目录。 */
  public Path stagingDirectory() {
    return stagingDirectory;
  }

  /** 单次调用的全文捕获预算。 */
  public long captureBudgetBytes() {
    return captureBudgetBytes;
  }

  /**
   * 创建 owner-only 中转文件：随机唯一后缀，0400/0600 语义，拒绝符号链接。
   *
   * @param callId 调用标识，只用于生成可读前缀
   */
  public Path createStagingFile(String callId) throws IOException {
    String prefix = sanitize(callId);
    for (int attempt = 0; attempt < 10; attempt++) {
      String unique = UUID.randomUUID().toString().substring(0, 8);
      Path candidate = stagingDirectory.resolve(prefix + "-" + unique + STAGING_SUFFIX);
      try {
        createOwnerOnlyFile(candidate);
        return candidate;
      } catch (FileAlreadyExistsException collision) {
        // 随机后缀碰撞：重试。
      }
    }
    throw new IOException("cannot create a unique text staging file for " + prefix);
  }

  /**
   * 以 CREATE_NEW、非符号链接、owner-only 语义创建文件。
   *
   * <p>POSIX 上把 0600 作为创建属性一起提交，因此不存在“先建后改权限”的可见窗口；其它文件系统只能退回创建后的 owner-only 收紧（Windows 语义）。
   */
  private static void createOwnerOnlyFile(Path candidate) throws IOException {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    FileChannel channel;
    if (posix) {
      channel =
          FileChannel.open(
              candidate,
              Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              PosixFilePermissions.asFileAttribute(
                  Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
    } else {
      channel =
          FileChannel.open(
              candidate,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      File file = candidate.toFile();
      file.setReadable(false, false);
      file.setReadable(true, true);
      file.setWritable(false, false);
      file.setWritable(true, true);
    }
    channel.close();
  }

  /**
   * 原子发布中转文件为 durable 全文，返回其绝对路径。
   *
   * <p>发布只做一次 move；目标名继承中转文件的唯一后缀，因此并发调用不会互相覆盖。
   */
  public Path publish(Path stagingFile) throws IOException {
    Objects.requireNonNull(stagingFile, "stagingFile");
    String stagingName = stagingFile.getFileName().toString();
    if (!stagingName.endsWith(STAGING_SUFFIX)) {
      throw new IllegalArgumentException("staging file must end with " + STAGING_SUFFIX);
    }
    String textName =
        stagingName.substring(0, stagingName.length() - STAGING_SUFFIX.length()) + TEXT_SUFFIX;
    Path target = textDirectory.resolve(textName);
    try {
      return Files.move(stagingFile, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException error) {
      return Files.move(stagingFile, target);
    }
  }

  /** 清理未发布的中转文件；已发布全文不受影响。 */
  void deleteStagingQuietly(Path stagingFile) {
    if (stagingFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(stagingFile);
    } catch (IOException ignored) {
      // 残留中转文件由下一次启动的 staging 清理收敛。
    }
  }

  private static Path prepareDirectory(Path directory, String name) {
    Path path = Objects.requireNonNull(directory, name).toAbsolutePath().normalize();
    try {
      Files.createDirectories(path);
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(name + " must be a directory: " + path);
      }
      applyOwnerOnlyDirectoryPermissions(path);
      return path;
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot create " + name + ": " + path, error);
    }
  }

  private static void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          directory,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
      return;
    }
    File file = directory.toFile();
    file.setReadable(false, false);
    file.setReadable(true, true);
    file.setWritable(false, false);
    file.setWritable(true, true);
    file.setExecutable(false, false);
    file.setExecutable(true, true);
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
