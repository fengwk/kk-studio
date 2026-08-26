package fun.fengwk.kkstudio.harness.runtime.permission;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.Objects;

/** Shared validation for permission map keys at runtime and persistence boundaries. */
public final class PermissionKeyValidator {

  /** The exact key used for rules that apply to every Agent Tool. */
  public static final String GLOBAL_KEY = "*";

  private PermissionKeyValidator() {}

  /**
   * Requires an exact global key or a canonical {@link AgentToolId}.
   *
   * @param key permission map key
   * @param field field name used in the validation message
   */
  public static void requireValid(String key, String field) {
    Objects.requireNonNull(field, "field");
    if (GLOBAL_KEY.equals(key)) {
      return;
    }
    if (key == null || key.isBlank()) {
      throw invalid(field, null);
    }
    try {
      new AgentToolId(key);
    } catch (IllegalArgumentException error) {
      throw invalid(field, error);
    }
  }

  private static IllegalArgumentException invalid(String field, Throwable cause) {
    String message = field + " must be '*' or a canonical AgentToolId";
    return cause == null
        ? new IllegalArgumentException(message)
        : new IllegalArgumentException(message, cause);
  }
}
