package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 验证 Environment 安装 code 的随机格式与 SHA-256 摘要确定性。 */
class EnvironmentInstallCodesTest {

  private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final Pattern HEX64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  /** 验证 generate() 产生的 code 满足 32 字节 base64url 无填充格式且多次生成具有高熵随机性。 */
  @Test
  void generateProduces32BytesBase64UrlRandomCodes() {
    Set<String> generatedCodes = new HashSet<>();
    int samples = 100;

    for (int i = 0; i < samples; i++) {
      String code = EnvironmentInstallCodes.generate();
      assertNotNull(code);
      assertEquals(43, code.length());
      assertTrue(
          BASE64URL_PATTERN.matcher(code).matches(),
          "code should match 43 chars base64url syntax: " + code);
      byte[] decoded = Base64.getUrlDecoder().decode(code);
      assertEquals(32, decoded.length, "decoded code must be exactly 32 bytes");
      generatedCodes.add(code);
    }

    assertEquals(samples, generatedCodes.size(), "each generated code should be unique");
  }

  /** 验证 hash(code) 计算的 SHA-256 摘要满足输出规范性、结果确定性以及对已知向量的正确性。 */
  @Test
  void hashIsDeterministicAndProducesValidSha256Hex() {
    String code = EnvironmentInstallCodes.generate();
    String firstHash = EnvironmentInstallCodes.hash(code);
    String secondHash = EnvironmentInstallCodes.hash(code);

    assertNotNull(firstHash);
    assertEquals(64, firstHash.length());
    assertTrue(HEX64_PATTERN.matcher(firstHash).matches());
    assertEquals(firstHash, secondHash, "hashing the same code must yield identical digests");

    String anotherCode = EnvironmentInstallCodes.generate();
    assertNotEquals(
        firstHash,
        EnvironmentInstallCodes.hash(anotherCode),
        "different codes must produce different digests");

    // 已知测试向量验证 SHA-256 确定性输出
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        EnvironmentInstallCodes.hash("abc"));
  }

  /** 验证 hash(null) 拒绝 null 输入。 */
  @Test
  void hashRejectsNull() {
    assertThrows(NullPointerException.class, () -> EnvironmentInstallCodes.hash(null));
  }
}
