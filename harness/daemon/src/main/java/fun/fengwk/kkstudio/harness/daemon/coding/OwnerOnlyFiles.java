package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 受控临时产物共享的 owner-only 文件系统原语：POSIX 目录 0700、文件 0600；支持 ACL 的文件系统（Windows）以当前进程用户为唯一主体建立 owner-only
 * ACL。两种平台都拒绝受控可信根之下的符号链接路径分量。
 *
 * <p>POSIX 上把 0700/0600 作为创建属性一起提交，因此不存在“先建后改权限”的可见窗口。Windows 上同样把单条 owner-only ACL 作为 {@code
 * acl:acl} 创建属性提交，并在创建后立即用 {@link AclFileAttributeView#setAcl} 收敛并读回校验：只要读回结果里出现非当前
 * 进程用户的授权，或以任何方式无法完成设置、读取，就以 {@link IOException} fail-closed，绝不静默宣称已达成 owner-only。平台既不支持 posix 也不支持
 * acl 时同样失败关闭，不再退回创建后的布尔权限设置（{@code File#setReadable} 等在 Windows 上是静默 no-op）。
 *
 * <p>ACL 平台上每一条新创建的目录分量都会在创建下一条之前完成 owner-only 收敛与读回校验，因此可信根之下不会留下未校验的中间目录。
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

  /** ACL 视图的初始创建属性名，Windows provider 用它把 DACL 与创建合并成一次系统调用。 */
  private static final String ACL_ATTRIBUTE_NAME = "acl:acl";

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
   * 创建缺失的目录链：POSIX 上把 0700 作为创建属性提交给每个新目录；ACL 平台逐级收起，见 {@link
   * #createAndTightenAclDirectories(Path)}。
   */
  private static void createOwnerOnlyDirectories(Path directory) throws IOException {
    if (isPosix()) {
      Files.createDirectories(directory, ownerOnlyDirectoryAttribute());
      return;
    }
    if (isAcl()) {
      createAndTightenAclDirectories(directory);
      return;
    }
    throw unsupportedFileSystem(directory);
  }

  /**
   * ACL 平台逐级创建缺失目录：每一条新分量都用 {@code acl:acl} 创建属性原子给出 owner-only ACL，并在创建下一条之前完成收敛与读回校验，
   * 因此可信根之下不会留下未校验的中间目录。
   */
  private static void createAndTightenAclDirectories(Path directory) throws IOException {
    Deque<Path> missing = new ArrayDeque<>();
    Path existing = directory.toAbsolutePath().normalize();
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      missing.push(existing);
      existing = existing.getParent();
      if (existing == null) {
        throw new IOException("no existing ancestor directory for: " + directory);
      }
    }
    if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("path component must be a directory: " + existing);
    }
    for (Path component : missing) {
      Files.createDirectory(component, ownerOnlyAclAttribute(true));
      applyOwnerOnlyAcl(component, true);
    }
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
    if (isAcl()) {
      Files.createDirectory(directory, ownerOnlyAclAttribute(true));
      applyOwnerOnlyAcl(directory, true);
      return;
    }
    throw unsupportedFileSystem(directory);
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
    if (isAcl()) {
      try (FileChannel channel =
          FileChannel.open(
              candidate,
              Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              ownerOnlyAclAttribute(false))) {
        // 只创建。
      }
      applyOwnerOnlyAcl(candidate, false);
      return;
    }
    throw unsupportedFileSystem(candidate);
  }

  /** 收敛目录权限：POSIX 显式 0700，ACL 平台收敛并读回校验，平台不支持时失败关闭。 */
  static void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
    if (isPosix()) {
      Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY_PERMISSIONS);
      return;
    }
    if (isAcl()) {
      applyOwnerOnlyAcl(directory, true);
      return;
    }
    throw unsupportedFileSystem(directory);
  }

  /**
   * 用单条当前进程用户 ALLOW 条目替换 ACL，并读回校验只剩当前用户的授权。
   *
   * <p>创建时的 owner-only 创建属性可能被平台按父目录继承规则追加其他主体的 ACE；这里在收敛后读回，只要出现非当前用户的授权就以 {@link IOException}
   * fail-closed，因此无论平台的继承语义如何都不会静默留下更宽的权限。
   */
  private static void applyOwnerOnlyAcl(Path target, boolean directory) throws IOException {
    AclFileAttributeView view =
        Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("ACL file attribute view is unavailable: " + target);
    }
    List<AclEntry> expected = List.of(ownerOnlyAclEntry(currentUserPrincipal(), directory));
    view.setAcl(expected);
    if (!expected.equals(view.getAcl())) {
      throw new IOException("cannot enforce owner-only ACL: " + target);
    }
  }

  private static AclEntry ownerOnlyAclEntry(UserPrincipal owner, boolean directory) {
    AclEntry.Builder builder =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(ownerOnlyAclPermissions(directory));
    if (directory) {
      builder.setFlags(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT));
    }
    return builder.build();
  }

  private static Set<AclEntryPermission> ownerOnlyAclPermissions(boolean directory) {
    EnumSet<AclEntryPermission> permissions =
        EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.SYNCHRONIZE);
    if (directory) {
      permissions.add(AclEntryPermission.DELETE_CHILD);
    }
    return permissions;
  }

  /** ACL 创建属性：单条当前进程用户 ALLOW，目录带 FILE_INHERIT/DIRECTORY_INHERIT 让其下的新子项同样 owner-only。 */
  private static FileAttribute<List<AclEntry>> ownerOnlyAclAttribute(boolean directory)
      throws IOException {
    List<AclEntry> acl = List.of(ownerOnlyAclEntry(currentUserPrincipal(), directory));
    return new FileAttribute<>() {
      @Override
      public String name() {
        return ACL_ATTRIBUTE_NAME;
      }

      @Override
      public List<AclEntry> value() {
        return acl;
      }
    };
  }

  /** 当前进程用户主体；由用户主体服务解析当前登录名，绝不使用可能是 Administrators 组的对象所有者。 */
  private static UserPrincipal currentUserPrincipal() throws IOException {
    String userName = System.getProperty("user.name");
    if (userName == null || userName.isBlank()) {
      throw new IOException("cannot determine the current process user name");
    }
    return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(userName);
  }

  private static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static boolean isAcl() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("acl");
  }

  private static IOException unsupportedFileSystem(Path target) {
    return new IOException(
        "owner-only permissions require posix or acl support on the filesystem: " + target);
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
