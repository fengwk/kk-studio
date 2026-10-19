package fun.fengwk.kkstudio.canvas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canvas 模块组的 SHA-256 摘要工具（canvas 不依赖 harness-common，故单独持有）；缺失算法视为环境损坏。 */
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

  /** 文本按 UTF-8 编码后的 SHA-256，64 位小写十六进制。 */
  public static String hex(String text) {
    Objects.requireNonNull(text, "text");
    return HexFormat.of().formatHex(newDigest().digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
