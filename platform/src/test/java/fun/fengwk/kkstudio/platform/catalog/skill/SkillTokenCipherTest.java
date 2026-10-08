package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialIntegrityException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** Skill 令牌加密边界契约：往返一致、AAD 绑定 packageName、随机 nonce、主密钥不可用或密文被篡改时 fail closed。 */
class SkillTokenCipherTest {

  private static final String KEY = "0123456789abcdef0123456789abcdef";

  @TempDir Path tempDir;

  private SkillTokenCipher cipherWithKeyFile(String keyFileName) throws IOException {
    Path keyFile = tempDir.resolve(keyFileName);
    Files.write(keyFile, KEY.getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    return new SkillTokenCipher(
        new PluginCredentialCodec(),
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
  }

  /** 测试意图：加解密往返一致，密文不含明文，且 AAD 绑定 packageName 使密文无法跨包解密。 */
  @Test
  void roundTripsAndBindsPackageName() throws IOException {
    SkillTokenCipher cipher = cipherWithKeyFile("bound.key");

    byte[] encrypted = cipher.encrypt("pkg-a", "secret-token");

    assertEquals("secret-token", cipher.decrypt("pkg-a", encrypted));
    assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("secret-token"));
    assertThrows(
        PluginCredentialIntegrityException.class, () -> cipher.decrypt("pkg-b", encrypted));
  }

  /** 测试意图：每次加密使用新的随机 nonce，同一明文的密文不相同。 */
  @Test
  void usesRandomNonce() throws IOException {
    SkillTokenCipher cipher = cipherWithKeyFile("nonce.key");

    byte[] first = cipher.encrypt("pkg", "same-token");
    byte[] second = cipher.encrypt("pkg", "same-token");

    assertFalse(Arrays.equals(first, second));
  }

  /** 测试意图：密文被篡改时解密 fail closed，绝不返回损坏或明文降级结果。 */
  @Test
  void failsClosedOnTamperedCiphertext() throws IOException {
    SkillTokenCipher cipher = cipherWithKeyFile("tamper.key");
    byte[] encrypted = cipher.encrypt("pkg", "secret-token");
    encrypted[encrypted.length - 1] ^= 0x01;

    assertThrows(PluginCredentialIntegrityException.class, () -> cipher.decrypt("pkg", encrypted));
  }

  /** 测试意图：部署主密钥不可用时读写都 fail closed，绝不退化为明文或跳过加密。 */
  @Test
  void failsClosedWhenKeyUnavailable() {
    SkillTokenCipher cipher =
        new SkillTokenCipher(
            new PluginCredentialCodec(),
            new PluginCredentialKeyLoader(
                tempDir.resolve("missing.key").toAbsolutePath().toString()));

    assertThrows(IllegalStateException.class, () -> cipher.encrypt("pkg", "secret-token"));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt("pkg", new byte[] {1, 2, 3, 4}));
  }
}
