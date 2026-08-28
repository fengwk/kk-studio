package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;

/** Goal 特性常量与元数据定义。 */
public final class GoalFeature {

  public static final ContributorId CONTRIBUTOR_ID = BuiltinHarnessContributor.ID;
  public static final String STATE_TYPE = "goal.state";

  private GoalFeature() {}
}
