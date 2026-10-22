package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * Contributor 的 canonical durable 身份：小写 dotted/dashed 标识符（如 {@code core}、{@code com.example.goal}、
 * {@code pi-base}），长度有界。
 */
public record ContributorId(String value) {

  /** 最大字符数，与其它 canonical 标识符一致。 */
  public static final int MAX_LENGTH = Identifiers.MAX_LENGTH;

  public ContributorId {
    Objects.requireNonNull(value, "value");
    Identifiers.requireCanonical(value, "contributorId");
  }

  @Override
  public String toString() {
    return value;
  }
}
