package fun.fengwk.kkstudio.harness.api.history;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * canonical 标识符校验的单一实现：{@link CustomMessagePayload} 的 contributorId / customType / rendererKey
 * 在构造时校验，history codec 在解码时先按字段顺序显式前置校验，两侧共用同一规则与文案。
 */
public final class CanonicalIdentifiers {

  /** canonical 小写 dotted/dashed 标识符的最大字符数。 */
  private static final int MAX_IDENTIFIER_CHARS = 64;

  private static final Pattern CANONICAL_IDENTIFIER =
      Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private CanonicalIdentifiers() {}

  /**
   * 校验 canonical 小写 dotted/dashed 标识符（contributorId / customType / rendererKey）：非 null、小写字母数字段以 单个
   * {@code .} 或 {@code -} 分隔、无前导/尾随/连续分隔符、长度不超过 {@link #MAX_IDENTIFIER_CHARS}。
   */
  public static String requireCanonicalIdentifier(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.length() > MAX_IDENTIFIER_CHARS || !CANONICAL_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field
              + " must be a lowercase dotted/dashed identifier of at most "
              + MAX_IDENTIFIER_CHARS
              + " chars: "
              + value);
    }
    return value;
  }
}
