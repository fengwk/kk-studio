package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.UUID;

/** Wire 上 canonical UUID 文本的共享校验。 */
final class DaemonWireUuids {

  private DaemonWireUuids() {}

  static UUID parse(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", error);
    }
  }
}
