package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/** Tool 对所属 contributor 某一 customType 的访问声明。 */
public record StateDeclaration(String customType, StateMode mode) {

  public StateDeclaration {
    customType = Identifiers.requireCanonical(customType, "customType");
    mode = Objects.requireNonNull(mode, "mode");
  }
}
