package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.util.Objects;
import java.util.regex.Pattern;

/** Durable Tool binding 内插件标识符的 canonical 校验。 */
final class ToolBindingIdentifiers {

  private static final int MAX_LENGTH = 64;
  private static final Pattern CANONICAL = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private ToolBindingIdentifiers() {}

  static String requireCanonical(String value, String context) {
    Objects.requireNonNull(value, context);
    if (value.length() > MAX_LENGTH || !CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          context
              + " must be a lowercase dotted/dashed identifier of at most "
              + MAX_LENGTH
              + " chars: "
              + value);
    }
    return value;
  }
}
