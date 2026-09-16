package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 针对 {@link DaemonTokenFile} 的行为断言测试。
 *
 * <p>覆盖凭证文件权限校验、格式限制、防路径遍历与符号链接拒绝、读取剔除外围空白、空值 fail-closed 以及 toString 防敏感信息泄漏等语义。
 */
class DaemonTokenFileTest {

  @TempDir Path tempDir;

  /** 验证 validate 接受存在的 owner-only 普通文件（POSIX 下 0600），并返回绝对规范化的路径。 */
  @Test
  void validateAcceptsOwnerOnlyRegularFile() throws IOException {
    Path tokenFile = tempDir.resolve("token.txt");
    Files.writeString(tokenFile, "valid-token-content");
    if (isPosixSupported()) {
      Files.setPosixFilePermissions(
          tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    Path validated = DaemonTokenFile.validate(tokenFile);

    assertEquals(tokenFile.toAbsolutePath().normalize(), validated);
    assertEquals(validated, validated.toAbsolutePath().normalize());
  }

  /** 验证 validate 在 POSIX 文件系统上严格拒绝组或其他人具有任何读写执行权限的文件（如 0644、0640、0666、0200 等）。 */
  @Test
  void validateRejectsNonOwnerOnlyPermissionsOnPosix() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统支持以测试文件模式位");
    Path tokenFile = tempDir.resolve("insecure-token.txt");
    Files.writeString(tokenFile, "secret");

    // 0644: 组与其他用户可读
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(tokenFile));

    // 0640: 组可读
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-r-----"));
    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(tokenFile));

    // 0666: 组与其他用户可写
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-rw-rw-"));
    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(tokenFile));

    // 0200: owner 不可读
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("-w-------"));
    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(tokenFile));
  }

  /** 验证 validate 拒绝目录路径，即便该目录仅有 owner 权限。 */
  @Test
  void validateRejectsDirectory() throws IOException {
    Path subDir = Files.createDirectory(tempDir.resolve("dir-token"));
    if (isPosixSupported()) {
      Files.setPosixFilePermissions(
          subDir,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    }

    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(subDir));
  }

  /** 验证 validate 拒绝符号链接（即使指向完全合规的普通文件），防止链接劫持。 */
  @Test
  void validateRejectsSymbolicLink() throws IOException {
    Path realToken = tempDir.resolve("real-token.txt");
    Files.writeString(realToken, "secret-token");
    if (isPosixSupported()) {
      Files.setPosixFilePermissions(
          realToken, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    Path symlink = tempDir.resolve("token-symlink.txt");
    Files.createSymbolicLink(symlink, realToken);

    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(symlink));
  }

  /** 验证 validate 拒绝不存在的文件路径。 */
  @Test
  void validateRejectsNonExistentPath() {
    Path missing = tempDir.resolve("non-existent-token.txt");

    assertThrows(IllegalArgumentException.class, () -> DaemonTokenFile.validate(missing));
  }

  /** 验证 validate 在传入 null 时抛出 NullPointerException。 */
  @Test
  void validateRejectsNull() {
    assertThrows(NullPointerException.class, () -> DaemonTokenFile.validate(null));
  }

  /** 验证 read 正确读取凭证文本并剔除首尾空白字符（包括换行、回车、空格与制表符）。 */
  @Test
  void readStripsSurroundingWhitespace() throws IOException {
    Path tokenFile = tempDir.resolve("whitespace-token.txt");
    Files.writeString(tokenFile, " \r\n\t  token-secret-abc-123 \n\t ");

    String token = DaemonTokenFile.read(tokenFile);

    assertEquals("token-secret-abc-123", token);
  }

  /** 验证 read 在文件为空或仅包含空白字符时抛出 IllegalStateException。 */
  @Test
  void readRejectsEmptyOrWhitespaceOnlyFile() throws IOException {
    Path emptyFile = tempDir.resolve("empty.txt");
    Files.writeString(emptyFile, "");
    assertThrows(IllegalStateException.class, () -> DaemonTokenFile.read(emptyFile));

    Path whitespaceFile = tempDir.resolve("whitespace.txt");
    Files.writeString(whitespaceFile, "  \n\r\t  ");
    assertThrows(IllegalStateException.class, () -> DaemonTokenFile.read(whitespaceFile));
  }

  /** 验证在校验通过后若凭证文件被删除，read 会抛出包装了 NoSuchFileException 的 UncheckedIOException。 */
  @Test
  void readThrowsUncheckedIOExceptionWhenFileDeletedAfterValidation() throws IOException {
    Path tokenFile = tempDir.resolve("ephemeral-token.txt");
    Files.writeString(tokenFile, "ephemeral-token");
    if (isPosixSupported()) {
      Files.setPosixFilePermissions(
          tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    Path validated = DaemonTokenFile.validate(tokenFile);
    Files.delete(validated);

    UncheckedIOException error =
        assertThrows(UncheckedIOException.class, () -> DaemonTokenFile.read(validated));
    assertInstanceOf(NoSuchFileException.class, error.getCause());
  }

  /** 验证凭证敏感内容绝不会通过 validate 返回的 Path 对象的 toString 暴露。 */
  @Test
  void tokenValueDoesNotLeakThroughToString() throws IOException {
    Path tokenFile = tempDir.resolve("secret-token.txt");
    String secret = "sensitive-token-payload-xyz-987654";
    Files.writeString(tokenFile, secret);
    if (isPosixSupported()) {
      Files.setPosixFilePermissions(
          tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    Path validated = DaemonTokenFile.validate(tokenFile);

    assertFalse(validated.toString().contains(secret), "validate 返回的 Path 字符串表示不得泄露凭证正文");
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
}
