package fun.fengwk.kkstudio.harness.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon 注册凭证文件读取边界：凭证只以 owner-only 普通文件的形式存在，进程不持久化其文本。
 *
 * <p>文件必须是绝对路径下的现存普通文件（不跟随符号链接），在支持 POSIX 的文件系统上不得有 group/other 权限位。任何不符合的输入都在启动期 fail
 * closed，而不是带着可疑凭证继续握手。
 *
 * <p>本类型不提供 {@code toString} 秘密回显：它只承载路径，凭证文本仅在 {@link #read} 的返回值中出现。
 */
final class DaemonTokenFile {

  private DaemonTokenFile() {}

  /** 校验凭证文件路径与权限；不读取内容。 */
  static Path validate(Path tokenFile) {
    Path path = requireAbsolute(tokenFile);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "registration token file must be an existing regular file: " + path, error);
    }
    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
      throw new IllegalArgumentException(
          "registration token file must be a regular file, not a link or directory: " + path);
    }
    requireOwnerOnly(path);
    return path;
  }

  /**
   * 读取凭证文本：去除外围空白（凭证是 opaque token，换行只可能来自写文件的 shell）后必须非空白。
   *
   * @throws IllegalStateException 文件在启动校验后变得不可读或为空
   */
  static String read(Path tokenFile) {
    Objects.requireNonNull(tokenFile, "tokenFile");
    String raw;
    try {
      raw = Files.readString(tokenFile, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read registration token file: " + tokenFile, error);
    }
    String token = raw.strip();
    if (token.isEmpty()) {
      throw new IllegalStateException("registration token file must not be empty: " + tokenFile);
    }
    return token;
  }

  private static Path requireAbsolute(Path value) {
    Path path = Objects.requireNonNull(value, "tokenFile");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("registration token file must be an absolute path");
    }
    return path.normalize();
  }

  private static void requireOwnerOnly(Path path) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    Set<PosixFilePermission> permissions;
    try {
      permissions = Files.getPosixFilePermissions(path);
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot inspect registration token file: " + path, error);
    }
    Set<PosixFilePermission> groupOrOther =
        Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);
    for (PosixFilePermission permission : groupOrOther) {
      if (permissions.contains(permission)) {
        throw new IllegalArgumentException(
            "registration token file must not grant group/other permissions: " + path);
      }
    }
    if (!permissions.contains(PosixFilePermission.OWNER_READ)) {
      throw new IllegalArgumentException(
          "registration token file must be readable by its owner: " + path);
    }
  }
}
