package fun.fengwk.kkstudio.platform.environment;

import fun.fengwk.kkstudio.common.hash.Sha256;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Environment 安装临时 code 编解码器：生成 32 字节 base64url 随机 code 及其 SHA-256 摘要。 */
public final class EnvironmentInstallCodes {

  private static final int CODE_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private EnvironmentInstallCodes() {}

  /** 生成 32 字节安全随机 base64url（无填充）code。 */
  public static String generate() {
    byte[] bytes = new byte[CODE_BYTES];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** 计算 code 的 SHA-256 小写十六进制摘要。 */
  public static String hash(String code) {
    Objects.requireNonNull(code, "code");
    return Sha256.hex(code);
  }
}
