package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical durable identity of an Environment route binding.
 *
 * <p>Environment display names are reusable and can be re-bound to different roots, so they cannot
 * identify history. Only the canonical lowercase UUID text is a durable route identity; display
 * names never enter the durable protocol types that carry this value.
 */
public record EnvironmentId(String value) {

  public EnvironmentId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("environmentId must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("environmentId must not contain surrounding whitespace");
    }
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(
          "environmentId must be a canonical lowercase UUID: " + value);
    }
    if (parsed.getMostSignificantBits() == 0 && parsed.getLeastSignificantBits() == 0) {
      throw new IllegalArgumentException("environmentId must not be nil");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
