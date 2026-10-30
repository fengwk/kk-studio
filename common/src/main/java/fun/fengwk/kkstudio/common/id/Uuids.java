package fun.fengwk.kkstudio.common.id;

import java.util.Objects;
import java.util.UUID;

/** Canonical UUID 文本解析：要求与 {@code UUID.toString()} 往返一致。 */
public final class Uuids {

  private Uuids() {}

  /** 解析 canonical UUID（{@code UUID.fromString} 往返一致）。 */
  public static UUID parseCanonical(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }
}
