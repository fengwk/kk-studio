package fun.fengwk.kkstudio.platform.harness.contributor;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;

import java.util.Objects;
import java.util.UUID;

/** 按冻结 Assistant Entry 加载特定 Contributor 作用域的只读 branch view。 */
public interface ContributorBranchViewLoader {

  /** 按 assistant entry id 和 canonical contributor id 加载 scoped branch view。 */
  BranchView load(UUID assistantEntryId, String contributorId);

  /** 便捷重载：接受类型化的 {@link ContributorId}。 */
  default BranchView load(UUID assistantEntryId, ContributorId contributorId) {
    Objects.requireNonNull(contributorId, "contributorId");
    return load(assistantEntryId, contributorId.value());
  }
}
