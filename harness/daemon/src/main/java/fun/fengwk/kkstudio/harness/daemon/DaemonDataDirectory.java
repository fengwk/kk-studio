package fun.fengwk.kkstudio.harness.daemon;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon 私有本地数据目录：owner-only 目录布局加进程级独占锁。
 *
 * <p>目录布局固定为 {@code <data-dir>/{daemon.lock,tmp,skills,skill-work/{staging,backup}}}。锁文件 {@code
 * daemon.lock} 由本进程在打开期间以 {@link FileChannel#tryLock()} 持有；同一目录上的第二个 Daemon 立即以明确错误失败，而不是并发写同一份数据。
 *
 * <p>{@code tmp} 是受控临时产物根：工具外化的受控临时 workspace 位于 {@code tmp/workspaces}，其不可变创建登记、保留期与 清扫由 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore} 承担，本类只负责创建受控根并收敛 owner-only 权限。
 *
 * <p>目录与文件权限在支持 POSIX 的文件系统上显式收敛为 owner-only（目录 0700、文件 0600）。
 */
public final class DaemonDataDirectory implements AutoCloseable {

  /** 进程独占锁文件名。 */
  public static final String LOCK_FILE_NAME = "daemon.lock";

  /** 默认数据目录：启动用户 HOME 下的 {@code .kk-studio}。 */
  public static final String DEFAULT_DIRECTORY_NAME = ".kk-studio";

  private static final String TMP = "tmp";
  private static final String SKILLS = "skills";
  private static final String SKILL_WORK = "skill-work";
  private static final String STAGING = "staging";
  private static final String BACKUP = "backup";

  private final Path root;
  private final Path tmp;
  private final Path skills;
  private final Path skillWork;
  private final Path skillStaging;
  private final Path skillBackup;
  private final FileChannel lockChannel;
  private final FileLock lock;

  private DaemonDataDirectory(
      Path root,
      Path tmp,
      Path skills,
      Path skillWork,
      Path skillStaging,
      Path skillBackup,
      FileChannel lockChannel,
      FileLock lock) {
    this.root = root;
    this.tmp = tmp;
    this.skills = skills;
    this.skillWork = skillWork;
    this.skillStaging = skillStaging;
    this.skillBackup = skillBackup;
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
    Path configured = Objects.requireNonNull(dataDir, "dataDir");
    if (!configured.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    try {
      Path root = canonicalDataRoot(configured);
      createOwnerOnlyDirectory(root);
      Path tmp = createOwnerOnlyDirectory(root.resolve(TMP));
      Path skills = createOwnerOnlyDirectory(root.resolve(SKILLS));
      Path skillWork = createOwnerOnlyDirectory(root.resolve(SKILL_WORK));
      Path skillStaging = createOwnerOnlyDirectory(skillWork.resolve(STAGING));
      Path skillBackup = createOwnerOnlyDirectory(skillWork.resolve(BACKUP));
      FileChannel channel = openOwnerOnlyLock(root.resolve(LOCK_FILE_NAME));
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
      return new DaemonDataDirectory(
          root, tmp, skills, skillWork, skillStaging, skillBackup, channel, lock);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot open daemon data directory: " + configured, error);
    }
  }

  /**
   * 解析可信数据根：对数据根的<b>父位置</b>做 {@code toRealPath}，从而合法容忍 macOS {@code /var}、{@code /tmp} 等 OS 标准前缀
   * alias；再拼回数据根本身。
   *
   * <p>数据根本身以及受控 {@code tmp}/{@code workspaces} 及其以下分量都不允许是符号链接/reparse：数据根这里显式拒绝，更下层由 {@link
   * fun.fengwk.kkstudio.harness.daemon.coding.OwnerOnlyFiles} 在可信根之下做组件 NOFOLLOW 检查。返回的路径已
   * canonical， 因此派生的 {@code tmp} 是上报给模型的 canonical 绝对路径。
   */
  private static Path canonicalDataRoot(Path configured) throws IOException {
    Path normalized = configured.normalize();
    Path parent = normalized.getParent();
    Path name = normalized.getFileName();
    if (parent == null || name == null) {
      throw new IllegalArgumentException("dataDir must not be a filesystem root: " + normalized);
    }
    Files.createDirectories(parent);
    Path root = parent.toRealPath().resolve(name);
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
      throw new IllegalArgumentException("data directory must not be a symbolic link: " + root);
    }
    return root;
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

  /** 受控临时产物根目录（{@code <data-dir>/tmp}）。 */
  public Path tmp() {
    return tmp;
  }

  /** 已安装技能根目录。 */
  public Path skills() {
    return skills;
  }

  /** 技能工作根目录（暂存与备份的父目录）。 */
  public Path skillWork() {
    return skillWork;
  }

  /** 技能安装暂存目录。 */
  public Path skillStaging() {
    return skillStaging;
  }

  /** 技能安装备份目录。 */
  public Path skillBackup() {
    return skillBackup;
  }

  /** 释放目录锁并关闭锁通道；失败不改变进程退出语义。 */
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

  /** 以 owner-only 权限打开锁文件；已有文件也会收敛权限，避免旧 umask 留下过宽权限。 */
  private static FileChannel openOwnerOnlyLock(Path lockFile) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Set<PosixFilePermission> permissions =
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      FileChannel channel =
          FileChannel.open(
              lockFile,
              Set.of(
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(permissions));
      Files.setPosixFilePermissions(lockFile, permissions);
      return channel;
    }
    FileChannel channel =
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    File file = lockFile.toFile();
    file.setReadable(false, false);
    file.setReadable(true, true);
    file.setWritable(false, false);
    file.setWritable(true, true);
    return channel;
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
