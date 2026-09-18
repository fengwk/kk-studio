package fun.fengwk.kkstudio.harness.runtime.permission;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.Objects;

/** Shared validation for permission map keys at runtime and persistence boundaries. */
public final class PermissionKeyValidator {

  /** The exact key used for rules that apply to every Agent Tool. */
  public static final String GLOBAL_KEY = "*";

  private PermissionKeyValidator() {}

  /**
   * Requires an exact global key or a valid model-visible tool name.
   *
   * @param key permission map key
   * @param field field name used in the validation message
   */
  public static void requireValid(String key, String field) {
    Objects.requireNonNull(field, "field");
    if (GLOBAL_KEY.equals(key)) {
      return;
    }
    if (!ToolDescriptor.isValidName(key)) {
      throw new IllegalArgumentException(field + " must be '*' or a valid model-visible tool name");
    }
  }
}
