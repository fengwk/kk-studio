package fun.fengwk.kkstudio.common.uri;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RFC 3986 百分号编码的共享实现：只保留 unreserved 字符，其余 UTF-8 字节一律写成大写 {@code %XX}。
 *
 * <p>本类产物同时是合法的单个 path segment 与合法的 query 参数值：空格写成 {@code %20} 而不是 {@code +}，{@code /}、 {@code
 * +}、{@code &}、{@code =} 也都被编码，因此调用方无需按位置选择不同编码，接收方按 RFC 3986 或按表单解码都还原出原值。 {@link
 * java.net.URLEncoder} 的 form-urlencoded 输出只在接收方按表单解码时成立，不能用于路径段。
 */
public final class UrlEncoding {

  private UrlEncoding() {}

  /**
   * 编码一个值；结果可直接拼进路径或 {@code name=value} 的 query。
   *
   * @throws NullPointerException value 为 null 时
   */
  public static String encode(String value) {
    Objects.requireNonNull(value, "value");
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length * 3);
    for (byte b : bytes) {
      int unsigned = b & 0xFF;
      if (isUnreserved(unsigned)) {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%');
        encoded.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
        encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
      }
    }
    return encoded.toString();
  }

  /** RFC 3986 unreserved：ALPHA / DIGIT / {@code -} / {@code .} / {@code _} / {@code ~}。 */
  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }
}
