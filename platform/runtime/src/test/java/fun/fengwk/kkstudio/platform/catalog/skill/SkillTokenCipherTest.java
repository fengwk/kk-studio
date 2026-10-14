package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;

/** Skill 令牌加密边界契约：往返一致、AAD 绑定 packageName、随机 nonce、主密钥不可用或密文被篡改时 fail closed。 */
class SkillTokenCipherTest {

  private static final String KEY = "0123456789abcdef0123456789abcdef";

  private static final String PACKAGE_NAME = "pkg-a";

  private static final String TOKEN = "secret-token";

  /**
   * 既有格式 1 的固定合成密文：key 为 {@link #KEY}，AAD 为长度前缀的 {@code skill-package} + {@code pkg-a} + int
   * 1，nonce 为 0x00..0x0b。它代表当前部署中已经存在的历史 Skill 密文，断言当前实现仍能读取它（不允许顺带破坏格式 1）。
   */
  private static final byte[] FORMAT_ONE_ENVELOPE =
      Base64.getDecoder().decode("AQABAgMEBQYHCAkKC16A2A68bP/Yx7cU0P1R8k3MF2omw8hzZq2D2Zo=");

  @TempDir Path tempDir;

  private SkillTokenCipher cipherWithKeyFile(String keyFileName) throws IOException {
    Path keyFile = tempDir.resolve(keyFileName);
    Files.write(keyFile, KEY.getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    return new SkillTokenCipher(new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
  }

  /** 测试意图：加解密往返一致，密文不含明文，且 AAD 绑定 packageName 使密文无法跨包解密。 */
  @Test
  void roundTripsAndBindsPackageName() throws IOException {
    SkillTokenCipher cipher = cipherWithKeyFile("bound.key");

    byte[] encrypted = cipher.encrypt(PACKAGE_NAME, TOKEN);

    assertEquals(TOKEN, cipher.decrypt(PACKAGE_NAME, encrypted));
    assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains(TOKEN));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt("pkg-b", encrypted));
  }

  /** 测试意图：既有格式 1 的历史密文仍然可读，证明格式 1 的 AAD 布局未被 Plugin 代际改造破坏。 */
  @Test
  void readsExistingFormatOneEnvelope() throws IOException {
    SkillTokenCipher cipher = cipherWithKeyFile("legacy.key");

    assertEquals(TOKEN, cipher.decrypt(PACKAGE_NAME, FORMAT_ONE_ENVELOPE));
    assertEquals(1, FORMAT_ONE_ENVELOPE[0], "fixture must stay format 1");
    assertThrows(IllegalStateException.class, () -> cipher.decrypt("pkg-b", FORMAT_ONE_ENVELOPE));
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
    byte[] encrypted = cipher.encrypt("pkg", TOKEN);
    encrypted[encrypted.length - 1] ^= 0x01;

    assertThrows(IllegalStateException.class, () -> cipher.decrypt("pkg", encrypted));
  }

  /** 测试意图：部署主密钥不可用时读写都 fail closed，绝不退化为明文或跳过加密。 */
  @Test
  void failsClosedWhenKeyUnavailable() {
    SkillTokenCipher cipher =
        new SkillTokenCipher(
            new PluginCredentialKeyLoader(
                tempDir.resolve("missing.key").toAbsolutePath().toString()));

    assertThrows(IllegalStateException.class, () -> cipher.encrypt("pkg", TOKEN));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt("pkg", new byte[] {1, 2, 3, 4}));
  }
}
