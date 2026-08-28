package fun.fengwk.kkstudio.platform.harness.contributor;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;

import java.util.UUID;

/** 按冻结 Assistant Entry 加载 contributor 只读 branch view。 */
public interface ContributorBranchViewLoader {

  BranchView load(UUID assistantEntryId);
}
