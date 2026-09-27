package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link WriteCapability} 与 {@link EditCapability} 共用的文本文件原子提交：先在目标目录准备 staging 文件，写入完整内容，
 * 继承目标受支持的属性， 再以一次原子替换把 staging 落到目标路径。
 *
 * <p>权限语义（目标已存在且为普通文件）：所在文件系统支持 POSIX 权限视图时，staging 先以 0600 建立并写入内容，内容全部落盘后才把权限收敛为目标的精确权限，
 * 最后原子替换。因此目标是 0444 之类的只读文件时，只要目标目录可写就仍能合法覆盖（写 staging 不依赖目标自身可写）；staging 的权限也只会从 0600 收敛到
 * 目标权限，持有内容期间绝不比目标更宽松。
 *
 * <p>权限语义（目标不存在）：使用平台默认创建语义建立 staging，POSIX 上即进程 umask 派生出的默认文件权限（例如 umask 022 时为 0644）；既不沿用保守的
 * 临时文件权限，也不放宽为固定宽权限。
 *
 * <p>owner 语义：POSIX 权限按文件 owner 落盘，提交进程对目标或目标目录没有足够权限时（例如目标目录不可写），staging 创建、内容写入、权限收敛或原子替换
 * 会作为提交前失败抛出。目标权限为 0000 时原内容不可读，write/edit 会在读取原文阶段先行拒绝。
 *
 * <p>平台边界：不支持 POSIX 权限视图的平台（例如 Windows）不假设也不模拟 POSIX 语义，ACL 等平台专属属性一律不做猜测性复制，权限由平台自身的继承规则
 * 决定。原子替换同样依赖平台能力：平台不支持原子改名时不降级为非原子覆盖，而是把 {@link java.nio.file.AtomicMoveNotSupportedException}
 * 作为提交前失败 抛出，目标路径保持原状。目标不是普通文件（目录、设备、FIFO、悬空符号链接等）时在任何 I/O 之前拒绝。
 *
 * <p>失败语义：staging 创建、内容写入、受支持属性的读取与设置、原子替换任一失败都作为提交前失败向上抛出，调用方据此报错，目标路径保持原状；只有全部成功， 提交才算成立。staging
 * 文件始终位于目标目录内并在结束时清理。
 */
final class TextFileCommit {

  /** staging 前缀：点号开头，不伪装成用户可见产物。 */
  private static final String STAGING_PREFIX = ".kk-mutation-";

  /** staging 的初始权限：内容写入之前先收紧到 owner-only。 */
  private static final Set<PosixFilePermission> OWNER_ONLY =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private TextFileCommit() {}

  /** 以 {@code bytes} 原子替换 {@code target}；目标不存在时创建，不改变调用方给出的内容。 */
  static void commit(Path target, byte[] bytes) throws IOException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(bytes, "bytes");
    Path parent = Objects.requireNonNull(target.getParent(), "target must have a parent directory");
    Files.createDirectories(parent);
    Set<PosixFilePermission> targetPermissions = existingPermissions(target);
    Path staging = createStagingFile(parent, targetPermissions != null);
    try {
      Files.write(staging, bytes);
      // 内容落盘后才收敛权限：staging 的权限只从 0600 收到目标权限，所以既不会因目标只读而写不进 staging，
      // 也不会在持有内容期间比目标更宽松。
      if (targetPermissions != null) {
        Files.setPosixFilePermissions(staging, targetPermissions);
      }
      // 一次原子替换：平台不支持原子改名时直接抛出提交前失败，绝不降级为非原子覆盖。
      Files.move(
          staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  /**
   * 目标已存在时返回其精确 POSIX 权限；目标不存在、平台不支持 POSIX 权限视图时返回 {@code null}。
   *
   * <p>非普通文件与悬空符号链接直接失败：替换它们等于静默丢弃设备节点或链接本身，不属于文本文件提交。
   */
  private static Set<PosixFilePermission> existingPermissions(Path target) throws IOException {
    if (Files.isSymbolicLink(target)) {
      throw new IOException("target is a symbolic link: " + target);
    }
    if (!Files.exists(target)) {
      return null;
    }
    if (!Files.isRegularFile(target)) {
      throw new IOException("target is not a regular file: " + target);
    }
    if (!Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
      return null;
    }
    return Files.getPosixFilePermissions(target);
  }

  /**
   * 在目标目录中新建唯一的 staging 文件。
   *
   * <p>名字使用随机 UUID 且以 {@code CREATE_NEW} 语义创建：既不会覆盖任何既有文件，也不会写入其它调用正在使用的 staging。
   *
   * <p>需要继承既有权限时先以 0600 建立；否则使用平台默认创建语义而不是固定权限，让 POSIX 上的新文件与进程 umask 保持一致。 名字冲突（概率可忽略）由 {@link
   * FileAlreadyExistsException} 作为提交前失败传播，既不覆盖也不重试。
   */
  private static Path createStagingFile(Path parent, boolean ownerOnly) throws IOException {
    Path candidate = parent.resolve(STAGING_PREFIX + UUID.randomUUID() + ".tmp");
    return ownerOnly
        ? Files.createFile(candidate, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        : Files.createFile(candidate);
  }
}
