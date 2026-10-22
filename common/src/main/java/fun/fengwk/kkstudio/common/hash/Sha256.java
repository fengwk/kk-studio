package fun.fengwk.kkstudio.common.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 摘要工具：JDK 必定提供该算法，缺失视为环境损坏。 */
public final class Sha256 {

  private Sha256() {}

  /** 新建一个 SHA-256 摘要，供需要分段更新的调用方使用。 */
  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  /** 字节的 SHA-256，64 位小写十六进制。 */
  public static String hex(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    return HexFormat.of().formatHex(newDigest().digest(bytes));
  }

  /** 文本按 UTF-8 编码后的 SHA-256，64 位小写十六进制。 */
  public static String hex(String text) {
    Objects.requireNonNull(text, "text");
    return hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
