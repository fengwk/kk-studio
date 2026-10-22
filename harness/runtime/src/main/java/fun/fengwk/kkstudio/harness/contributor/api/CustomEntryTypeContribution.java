package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * 冻结后的自定义 Entry type ownership：scoped 身份与 ownership 键 customType；ownership 实际是 {@code
 * (id.contributorId(), customType)}，不同 contributor 可以各自拥有同名 customType。
 */
public record CustomEntryTypeContribution(ContributionId id, String customType, int priority) {

  public CustomEntryTypeContribution {
    id = Objects.requireNonNull(id, "id");
    Identifiers.requireCanonical(customType, "customType");
  }
}
