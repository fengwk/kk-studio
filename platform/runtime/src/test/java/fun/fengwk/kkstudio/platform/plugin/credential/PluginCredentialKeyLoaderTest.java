package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;

import javax.crypto.SecretKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Optional;

/**
 * 部署主密钥加载器契约测试。
 *
 * <p>主密钥是进程启动时的部署级安全边界，只接受权限为 600（owner-only）且内容精确为 32 字节原始 AES 密钥的绝对路径文件。 成功加载后在进程内缓存，读取失败时
 * fail-closed 并不缓存失败。
 */
class PluginCredentialKeyLoaderTest {

  @TempDir Path tempDir;

  private static final String SECRET_MARKER = "test-secret-marker-32-byte-key!!";

  private Path createKeyFile(String filename, byte[] content, String permissions)
      throws IOException {
    Path file = tempDir.resolve(filename);
    Files.write(file, content);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
    return file;
  }

  private byte[] sample32ByteKey() {
    byte[] key = SECRET_MARKER.getBytes(StandardCharsets.UTF_8);
    assertEquals(PluginCredentialKeyLoader.KEY_BYTES, key.length);
    return key;
  }

  /** 恰好 32 bytes 且 owner-only(600) 的绝对路径文件应成功加载 AES 密钥，require() 正常返回。 */
  @Test
  void loadsAndRequiresValidOwnerOnly32ByteKey() throws IOException {
    byte[] keyBytes = sample32ByteKey();
    Path file = createKeyFile("valid-key.bin", keyBytes, "rw-------");

    PluginCredentialKeyLoader loader =
        new PluginCredentialKeyLoader(file.toAbsolutePath().toString());
    Optional<SecretKey> loaded = loader.load();

    assertTrue(loaded.isPresent(), "valid key file must be loaded");
    SecretKey key = loaded.get();
    assertEquals("AES", key.getAlgorithm());
    assertArrayEquals(keyBytes, key.getEncoded());
    assertNotNull(loader.require());
  }

  /** group 或 others 可读（644 与 640）的文件必须拒绝，require() 抛出异常且消息中只含路径不含密钥明文。 */
  @Test
  void rejectsGroupOrOthersReadableKeyFile() throws IOException {
    byte[] keyBytes = sample32ByteKey();

    for (String perm : new String[] {"rw-r--r--", "rw-r-----"}) {
      Path file = createKeyFile("wide-perm-" + perm + ".bin", keyBytes, perm);
      PluginCredentialKeyLoader loader =
          new PluginCredentialKeyLoader(file.toAbsolutePath().toString());

      assertTrue(loader.load().isEmpty(), "perm " + perm + " must be rejected");

      PluginKeyUnavailableException ex =
          assertThrows(
              PluginKeyUnavailableException.class,
              loader::require,
              "require() must throw when permission is " + perm);

      assertTrue(
          ex.getMessage().contains(file.toAbsolutePath().toString()),
          "error message must contain file path");
      assertFalse(
          ex.getMessage().contains(SECRET_MARKER), "error message must not leak secret material");
    }
  }

  /** 长度不是 32 字节（31 字节、33 字节）或空文件必须返回空。 */
  @Test
  void rejectsInvalidLengthsAndEmptyFile() throws IOException {
    byte[] short31 = new byte[31];
    Arrays.fill(short31, (byte) 1);
    Path shortFile = createKeyFile("short-31.bin", short31, "rw-------");

    byte[] long33 = new byte[33];
    Arrays.fill(long33, (byte) 2);
    Path longFile = createKeyFile("long-33.bin", long33, "rw-------");

    Path emptyFile = createKeyFile("empty-0.bin", new byte[0], "rw-------");

    assertTrue(
        new PluginCredentialKeyLoader(shortFile.toAbsolutePath().toString()).load().isEmpty());
    assertTrue(
        new PluginCredentialKeyLoader(longFile.toAbsolutePath().toString()).load().isEmpty());
    assertTrue(
        new PluginCredentialKeyLoader(emptyFile.toAbsolutePath().toString()).load().isEmpty());
  }

  /** 相对路径必须拒绝（必须是绝对路径）。 */
  @Test
  void rejectsRelativePath() {
    PluginCredentialKeyLoader loader = new PluginCredentialKeyLoader("relative/path/key.bin");
    assertTrue(loader.load().isEmpty(), "relative path must be rejected");
  }

  /** 未配置或空白路径必须返回空。 */
  @Test
  void rejectsUnconfiguredOrBlankPath() {
    assertTrue(new PluginCredentialKeyLoader(null).load().isEmpty());
    assertTrue(new PluginCredentialKeyLoader("").load().isEmpty());
    assertTrue(new PluginCredentialKeyLoader("   ").load().isEmpty());
  }

  /** 不存在的文件路径必须返回空。 */
  @Test
  void rejectsNonExistentFile() {
    Path nonExistent = tempDir.resolve("not-exist-key.bin");
    PluginCredentialKeyLoader loader =
        new PluginCredentialKeyLoader(nonExistent.toAbsolutePath().toString());
    assertTrue(loader.load().isEmpty());
  }

  /** 指向合法密钥的符号链接必须拒绝（NOFOLLOW_LINKS 语义）。 */
  @Test
  void rejectsSymbolicLinkToValidKey() throws IOException {
    byte[] keyBytes = sample32ByteKey();
    Path targetFile = createKeyFile("target-key.bin", keyBytes, "rw-------");
    Path symlinkFile = tempDir.resolve("symlink-key.bin");
    Files.createSymbolicLink(symlinkFile, targetFile);

    PluginCredentialKeyLoader loader =
        new PluginCredentialKeyLoader(symlinkFile.toAbsolutePath().toString());
    assertTrue(loader.load().isEmpty(), "symlink must be rejected due to NOFOLLOW_LINKS");
  }

  /** 缓存语义：成功加载后即使文件被删除或内容被篡改，依然返回进程内缓存的 SecretKey。 */
  @Test
  void cachesKeyOnceLoadedEvenIfFileIsDeletedOrOverwritten() throws IOException {
    byte[] keyBytes = sample32ByteKey();
    Path file = createKeyFile("cached-key.bin", keyBytes, "rw-------");

    PluginCredentialKeyLoader loader =
        new PluginCredentialKeyLoader(file.toAbsolutePath().toString());
    Optional<SecretKey> first = loader.load();
    assertTrue(first.isPresent());

    // 删除底层文件
    Files.delete(file);

    Optional<SecretKey> second = loader.load();
    assertTrue(second.isPresent(), "key must remain cached in memory after file is deleted");
    assertSame(first.get(), second.get(), "cached key instance must be returned");
  }

  /** 首次加载失败后再修复文件内容，后续 load() 应能成功（失败状态不进行永久缓存）。 */
  @Test
  void doesNotCacheFailureAndSucceedsOnceFileIsFixed() throws IOException {
    Path file = tempDir.resolve("fixable-key.bin");
    Files.write(file, new byte[16]); // 初始内容长度不合法
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

    PluginCredentialKeyLoader loader =
        new PluginCredentialKeyLoader(file.toAbsolutePath().toString());
    assertTrue(loader.load().isEmpty(), "first attempt with short file must fail");

    // 修复文件为合法的 32 字节密钥
    byte[] validKey = sample32ByteKey();
    Files.write(file, validKey);

    Optional<SecretKey> second = loader.load();
    assertTrue(second.isPresent(), "subsequent attempt must succeed after file is fixed");
    assertArrayEquals(validKey, second.get().getEncoded());
  }
}
