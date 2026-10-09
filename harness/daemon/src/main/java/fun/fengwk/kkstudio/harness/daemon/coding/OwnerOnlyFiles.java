package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 受控临时产物共享的 owner-only 文件系统原语：目录 0700、文件 0600，并拒绝受控可信根之下的符号链接路径分量。
 *
 * <p>POSIX 上把 0700/0600 作为创建属性一起提交，因此不存在“先建后改权限”的可见窗口。非 POSIX 文件系统只能退回创建后的 {@link File} owner-only
 * 收紧；此类收紧调用一旦报告失败就以 {@link IOException} fail-closed，绝不静默宣称已达成 owner-only。
 *
 * <p>符号链接防护只在<b>已 canonical 的可信根之下</b>做组件检查：调用方传入的可信根必须已经解析过 OS 前缀 alias（例如 macOS 的 {@code
 * /var}、{@code /tmp}），本类只从可信根的下一个分量起逐级拒绝符号链接/reparse，因此不会把操作系统标准 alias 误判为逃逸；本类也绝不对受控子路径调用 {@code
 * toRealPath}，避免跟随本应拒绝的链接。平台若不支持相应文件属性视图，本类不提供任何超出该组件检查的 reparse 语义保证。
 */
final class OwnerOnlyFiles {

  /** 受控目录的 owner-only 权限：0700。 */
  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY_PERMISSIONS =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);

  /** 受控文件的 owner-only 权限：0600。 */
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMISSIONS =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private OwnerOnlyFiles() {}

  /** 目录创建属性（0700），可让创建与权限收敛在同一次系统调用里完成。 */
  private static FileAttribute<Set<PosixFilePermission>> ownerOnlyDirectoryAttribute() {
    return PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS);
  }

  /** 文件创建属性（0600）。 */
  private static FileAttribute<Set<PosixFilePermission>> ownerOnlyFileAttribute() {
    return PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMISSIONS);
  }

  /** 创建（必要时）owner-only 目录并返回其绝对规范化路径；可信根之下出现符号链接或非目录分量时失败关闭。 */
  static Path ensureOwnerOnlyDirectory(Path trustedRoot, Path directory) throws IOException {
    rejectSymlinkedPathComponents(trustedRoot, directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("path exists but is not a directory: " + directory);
      }
      createOwnerOnlyDirectories(directory);
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("path must be a directory: " + directory);
    }
    applyOwnerOnlyDirectoryPermissions(directory);
    return directory;
  }

  /**
   * 创建缺失的目录链：POSIX 上把 0700 作为创建属性提交给每个新目录，父级新目录与末级同样安全，不存在“先建后改权限”的可见窗口。
   *
   * <p>非 POSIX 只能先创建再收紧（{@link #applyOwnerOnlyDirectoryPermissions}），失败即失败关闭。
   */
  private static void createOwnerOnlyDirectories(Path directory) throws IOException {
    if (isPosix()) {
      Files.createDirectories(directory, ownerOnlyDirectoryAttribute());
      return;
    }
    Files.createDirectories(directory);
  }

  /**
   * 以 owner-only 语义创建新目录；已存在时抛出 {@link FileAlreadyExistsException}。
   *
   * <p>POSIX 上把 0700 作为创建属性一起提交，不存在“先建后改权限”的可见窗口；可信根之下是符号链接时失败关闭。
   */
  static void createOwnerOnlyDirectory(Path trustedRoot, Path directory) throws IOException {
    rejectSymlinkedPathComponents(trustedRoot, directory);
    if (isPosix()) {
      Files.createDirectory(directory, ownerOnlyDirectoryAttribute());
      return;
    }
    Files.createDirectory(directory);
    applyOwnerOnlyDirectoryPermissions(directory);
  }

  /** 以 owner-only 语义创建新文件；已存在时抛出 {@link FileAlreadyExistsException}；可信根之下是符号链接时失败关闭。 */
  static void createOwnerOnlyFile(Path trustedRoot, Path candidate) throws IOException {
    rejectSymlinkedPathComponents(trustedRoot, candidate);
    if (isPosix()) {
      try (FileChannel channel =
          FileChannel.open(
              candidate,
              Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              ownerOnlyFileAttribute())) {
        // 只创建：句柄由 try-with-resources 关闭。
      }
      return;
    }
    try (FileChannel channel =
        FileChannel.open(
            candidate,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      // 只创建。
    }
    applyOwnerOnlyFilePermissions(candidate);
  }

  /** 收敛目录权限：支持 POSIX 时显式 0700；否则退回 {@link File} 的 owner-only 视图，失败即抛出。 */
  static void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
    if (isPosix()) {
      Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY_PERMISSIONS);
      return;
    }
    applyOwnerOnly(directory, true);
  }

  private static void applyOwnerOnlyFilePermissions(Path file) throws IOException {
    if (isPosix()) {
      Files.setPosixFilePermissions(file, OWNER_ONLY_FILE_PERMISSIONS);
      return;
    }
    applyOwnerOnly(file, false);
  }

  private static void applyOwnerOnly(Path target, boolean directory) throws IOException {
    File file = target.toFile();
    requireApplied(file.setReadable(false, false), "setReadable(false, false)", target);
    requireApplied(file.setReadable(true, true), "setReadable(true, true)", target);
    requireApplied(file.setWritable(false, false), "setWritable(false, false)", target);
    requireApplied(file.setWritable(true, true), "setWritable(true, true)", target);
    if (directory) {
      requireApplied(file.setExecutable(false, false), "setExecutable(false, false)", target);
      requireApplied(file.setExecutable(true, true), "setExecutable(true, true)", target);
    }
  }

  private static void requireApplied(boolean applied, String operation, Path target)
      throws IOException {
    if (!applied) {
      throw new IOException("cannot enforce owner-only permissions (" + operation + "): " + target);
    }
  }

  private static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  /**
   * 只检查可信根<b>之下</b>的路径分量是否存在符号链接/reparse：可信根本身及其 OS 前缀别名不受检查，也不算逃逸。
   *
   * <p>可信根之下的每个已存在分量都必须不是符号链接，且除末级外都必须是目录；末级允许是不存在的待创建项，也允许是已存在的普通文件（由 {@code CREATE_NEW}
   * 语义裁定冲突）。目标必须位于可信根之内，否则失败关闭。
   */
  private static void rejectSymlinkedPathComponents(Path trustedRoot, Path target)
      throws IOException {
    Path absoluteRoot = trustedRoot.toAbsolutePath().normalize();
    Path absolute = target.toAbsolutePath().normalize();
    if (!absolute.startsWith(absoluteRoot)) {
      throw new IOException("path escapes the trusted root: " + absolute);
    }
    int base = absoluteRoot.getNameCount();
    int count = absolute.getNameCount();
    Path current = absoluteRoot;
    for (int index = base; index < count; index++) {
      current = current.resolve(absolute.getName(index));
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      if (Files.isSymbolicLink(current)) {
        throw new IOException("path component must not be a symbolic link: " + current);
      }
      if (index < count - 1 && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("path component must be a directory: " + current);
      }
    }
  }
}
