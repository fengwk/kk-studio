package fun.fengwk.kkstudio.common.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** SHA-256 摘要工具：JDK 必定提供该算法，缺失视为环境损坏。 */
public final class Sha256 {

  /** 小写十六进制 SHA-256 文本的字数。 */
  public static final int HEX_LENGTH = 64;

  private static final Pattern CANONICAL_HEX = Pattern.compile("[0-9a-f]{64}");

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

  /**
   * 校验文本是 canonical 小写十六进制 SHA-256 摘要（64 个 {@code [0-9a-f]} 字符）并原样返回。
   *
   * <p>持久化身份键与 request hash 共用这一条规则；比较对象是摘要文本本身，不做大小写归一化。
   *
   * @param value 待校验的摘要文本，不能为 null
   * @param field 出现在异常文案中的字段名
   */
  public static String requireHex(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!CANONICAL_HEX.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " must be a lowercase SHA-256 hex string: " + value);
    }
    return value;
  }
}
