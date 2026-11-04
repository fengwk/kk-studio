package fun.fengwk.kkstudio.harness.api.tool;

import java.util.Objects;

/** Contributor Tool 对某一 customType 的冻结访问声明。 */
public record ContributorStateAccess(String customType, ContributorStateAccessMode mode) {

  public ContributorStateAccess {
    customType = ToolBindingIdentifiers.requireCanonical(customType, "customType");
    mode = Objects.requireNonNull(mode, "mode");
  }
}
