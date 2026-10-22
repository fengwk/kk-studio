package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;

/** Goal 特性常量与元数据定义。 */
final class GoalFeature {

  static final ContributorId CONTRIBUTOR_ID = BuiltinHarnessContributor.ID;
  static final String PROGRESS_TYPE = BuiltinHarnessContributor.GOAL_PROGRESS_TYPE;

  private GoalFeature() {}
}
