package fun.fengwk.kkstudio.harness.daemon;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon 私有本地数据目录：owner-only 目录布局加进程级独占锁。
 *
 * <p>目录布局固定为 {@code <data-dir>/{daemon.lock,resources/{text,staging,blobs}}}。锁文件 {@code
 * daemon.lock} 由本进程在打开期间以 {@link FileChannel#tryLock()} 持有；同一目录上的第二个 Daemon 立即以明确错误失败，而不是并发写同一份数据。
 *
 * <p>启动时只清理 {@code resources/staging} 下遗留的 {@code *.part} 中转文件（上一次进程崩溃的残留）；已发布的 {@code
 * resources/text} 与 {@code resources/blobs} 永不自动删除，durable history 可能仍引用其中的路径。
 *
 * <p>目录与文件权限在支持 POSIX 的文件系统上显式收敛为 owner-only（目录 0700、文件 0600）。
 */
public final class DaemonDataDirectory implements AutoCloseable {

  /** 进程独占锁文件名。 */
  public static final String LOCK_FILE_NAME = "daemon.lock";

  /** 默认数据目录：启动用户 HOME 下的 {@code .kk-studio}。 */
  public static final String DEFAULT_DIRECTORY_NAME = ".kk-studio";

  private static final String RESOURCES = "resources";
  private static final String TEXT = "text";
  private static final String STAGING = "staging";
  private static final String BLOBS = "blobs";
  private static final String STAGING_SUFFIX = ".part";

  private final Path root;
  private final Path resources;
  private final Path text;
  private final Path staging;
  private final Path blobs;
  private final FileChannel lockChannel;
  private final FileLock lock;

  private DaemonDataDirectory(
      Path root,
      Path resources,
      Path text,
      Path staging,
      Path blobs,
      FileChannel lockChannel,
      FileLock lock) {
    this.root = root;
    this.resources = resources;
    this.text = text;
    this.staging = staging;
    this.blobs = blobs;
    this.lockChannel = lockChannel;
    this.lock = lock;
  }

  /**
   * 创建（必要时）并锁定数据目录。
   *
   * @throws IllegalArgumentException 路径不是绝对路径、无法创建或不是目录
   * @throws IllegalStateException 同一目录已被另一个 Daemon 进程持有
   */
  public static DaemonDataDirectory open(Path dataDir) {
    Path root = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
    if (!root.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    try {
      createOwnerOnlyDirectory(root);
      Path resources = createOwnerOnlyDirectory(root.resolve(RESOURCES));
      Path text = createOwnerOnlyDirectory(resources.resolve(TEXT));
      Path staging = createOwnerOnlyDirectory(resources.resolve(STAGING));
      Path blobs = createOwnerOnlyDirectory(resources.resolve(BLOBS));
      FileChannel channel =
          FileChannel.open(
              root.resolve(LOCK_FILE_NAME),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException error) {
        lock = null;
      }
      if (lock == null) {
        channel.close();
        throw new IllegalStateException(
            "data directory is already in use by another daemon process: " + root);
      }
      DaemonDataDirectory directory =
          new DaemonDataDirectory(root, resources, text, staging, blobs, channel, lock);
      directory.cleanStaleStagingFiles();
      return directory;
    } catch (IOException error) {
      throw new UncheckedIOException("cannot open daemon data directory: " + root, error);
    }
  }

  /** 未显式配置时的默认数据目录；只计算路径，不创建也不加锁。 */
  public static Path defaultRoot() {
    return Path.of(System.getProperty("user.home"))
        .resolve(DEFAULT_DIRECTORY_NAME)
        .toAbsolutePath()
        .normalize();
  }

  /** 数据目录根（锁文件所在目录）。 */
  public Path root() {
    return root;
  }

  /** 全部本地资源根目录（不含锁文件）。 */
  public Path resources() {
    return resources;
  }

  /** 已发布的 durable 全文目录；内容只在显式清理时才可删除。 */
  public Path text() {
    return text;
  }

  /** owner-only 中转目录，只允许出现未发布的 {@code *.part} 文件。 */
  public Path staging() {
    return staging;
  }

  /** 内容寻址的不可变 Resource 导出根。 */
  public Path blobs() {
    return blobs;
  }

  /** 只清理本次启动前遗留的中转文件；已发布内容与 Resource 导出不会被动。 */
  private void cleanStaleStagingFiles() throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(staging, "*" + STAGING_SUFFIX)) {
      for (Path entry : entries) {
        BasicFileAttributes attributes =
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isRegularFile()) {
          Files.deleteIfExists(entry);
        }
      }
    }
  }

  @Override
  public void close() {
    try {
      if (lock.isValid()) {
        lock.release();
      }
    } catch (IOException ignored) {
      // 释放失败不改变进程退出语义；锁随进程结束自动失效。
    }
    try {
      lockChannel.close();
    } catch (IOException ignored) {
      // 同上。
    }
  }

  private static Path createOwnerOnlyDirectory(Path directory) throws IOException {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(directory);
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("data directory path must be a directory: " + directory);
    }
    applyOwnerOnlyDirectoryPermissions(directory);
    return directory;
  }

  /** 收敛目录权限：支持 POSIX 时显式 0700；否则退回 {@link File} 的 owner-only 视图（Windows 语义）。 */
  static void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
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
}
