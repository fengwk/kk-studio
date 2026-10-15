package fun.fengwk.kkstudio.platform.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 长度前缀 AAD 原语：逐字段写入 4 bytes 大端长度前缀与 UTF-8 字节，可再追加整型字段。
 *
 * <p>长度前缀使不同字段拼接不会产生歧义，因此调用方可以安全地把多个身份字段组成 AAD，而不用手工拼接字符串。本类只负责编码，不绑定任何身份语义。
 */
public final class LengthPrefixedAad {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  private LengthPrefixedAad() {}

  public static LengthPrefixedAad create() {
    return new LengthPrefixedAad();
  }

  /** 追加一个 UTF-8 字符串字段（前置 4 bytes 大端长度）。 */
  public LengthPrefixedAad field(String value) {
    Objects.requireNonNull(value, "value");
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeInt(bytes.length);
    out.writeBytes(bytes);
    return this;
  }

  /**
   * 追加一个可空 UTF-8 字段：先写 1 byte 存在标记（1=存在、0=缺失），存在时再写长度前缀与字节。
   *
   * <p>存在标记使「字段缺失」与「字段为空串」在 AAD 中不可互换，因此可空身份字段也能安全绑定。
   */
  public LengthPrefixedAad optionalField(String value) {
    if (value == null) {
      out.write(0);
      return this;
    }
    out.write(1);
    return field(value);
  }

  /** 追加一个 4 bytes 大端整数字段。 */
  public LengthPrefixedAad intValue(int value) {
    writeInt(value);
    return this;
  }

  public byte[] build() {
    return out.toByteArray();
  }

  private void writeInt(int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }
}
