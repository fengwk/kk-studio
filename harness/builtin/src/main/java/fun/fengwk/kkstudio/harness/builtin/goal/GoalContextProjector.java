package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjector;

import java.util.List;
import java.util.Map;

/** 把当前 active Goal 快照投影为下一次 model planning 的系统上下文。 */
public final class GoalContextProjector implements ContextProjector {

  @Override
  public List<ContextFragment> project(BranchView view) {
    GoalState state = GoalToolSupport.latest(view).orElse(null);
    if (state == null || state.status() != GoalStatus.ACTIVE) {
      return List.of();
    }
    String prompt =
        GoalPrompts.template("active-goal-context.md")
            .render(Map.of("goalJson", GoalToolSupport.envelope(state)));
    return List.of(new ContextFragment(prompt));
  }
}
