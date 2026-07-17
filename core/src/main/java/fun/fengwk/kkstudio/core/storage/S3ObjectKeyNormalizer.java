package fun.fengwk.kkstudio.core.storage;

import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;

/**
 * S3 对象键的统一校验入口。
 *
 * <p>所有面向固定 bucket 的服务端读取与浏览器预签名链路必须使用该工具校验 key， 避免签名键与实际读取键不一致。
 *
 * @author fengwk
 */
public final class S3ObjectKeyNormalizer {

  /** S3 object key 的 UTF-8 最大长度。 */
  public static final int MAX_KEY_LENGTH_BYTES = 1024;

  private S3ObjectKeyNormalizer() {}

  /**
   * 校验并返回对象键。对象键不做路径重写。
   *
   * @param key 原始对象键
   * @return 校验后的对象键
   * @throws IllegalArgumentException key 为空、含前导 /、含 . 或 .. 段、含控制字符或超过 UTF-8 长度上限
   */
  public static String normalize(String key) {
    Assert.hasText(key, "key must not be blank");
    if (key.startsWith("/")) {
      throw new IllegalArgumentException("key must not start with '/'");
    }
    if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "key UTF-8 length must not exceed " + MAX_KEY_LENGTH_BYTES + " bytes");
    }
    String[] segments = key.split("/", -1);
    for (String segment : segments) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException("key must not contain '.' or '..' path segments");
      }
    }
    for (int i = 0; i < key.length(); i++) {
      if (Character.isISOControl(key.charAt(i))) {
        throw new IllegalArgumentException("key must not contain control characters");
      }
    }
    return key;
  }
}
