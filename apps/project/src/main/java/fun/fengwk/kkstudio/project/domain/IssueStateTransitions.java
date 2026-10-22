package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;

/**
 * Issue 阶段流转策略：以 workflow 的 {@code next} 白名单为唯一依据，并显式区分正常边、业务阻塞/恢复与 DONE 重开。
 *
 * <p>正常边只能进入启用阶段；BLOCKED 由专用阻塞操作进入并记录原阶段与原因；恢复只能显式回到阻塞前阶段； DONE 只能由显式重开操作回到 INIT。依赖说明写在 Issue
 * 正文，这里不做依赖图、自动唤醒或门禁判定。
 */
public final class IssueStateTransitions {

  private final ProjectWorkflow workflow;

  public IssueStateTransitions(ProjectWorkflow workflow) {
    this.workflow = Objects.requireNonNull(workflow, "workflow");
  }

  /** 正常边：目标必须是当前阶段声明的白名单成员，且处于启用状态。 */
  public boolean canTransition(ProjectStateCode from, ProjectStateCode to) {
    if (from == null || to == null) {
      return false;
    }
    ProjectWorkflowState source = workflow.find(from).orElse(null);
    if (source == null) {
      return false;
    }
    ProjectWorkflowState target = workflow.find(to).orElse(null);
    return target != null && target.enabled() && source.next().contains(to);
  }

  /** 要求一次合法正常转移，返回目标阶段。 */
  public ProjectStateCode requireTransition(ProjectStateCode from, ProjectStateCode to) {
    if (!canTransition(from, to)) {
      throw new IllegalArgumentException("state " + from + " cannot transition to " + to);
    }
    return to;
  }

  /** 业务阻塞：BLOCKED 与 DONE 不能被阻塞；返回必须与 BLOCKED、原因一起原子保存的阻塞前阶段。 */
  public ProjectStateCode requireBlock(ProjectStateCode from, String reason) {
    ProjectWorkflowState source = workflow.require(from);
    ProjectStateCode blocked = ProjectWorkflowReservedState.BLOCKED.code();
    ProjectStateCode done = ProjectWorkflowReservedState.DONE.code();
    if (blocked.equals(source.state()) || done.equals(source.state())) {
      throw new IllegalArgumentException("state " + from + " cannot be blocked");
    }
    ProjectValidation.requireText(reason, "blockReason");
    return source.state();
  }

  /** 恢复：只能显式回到阻塞前阶段，且该阶段必须是仍启用的 INIT 或工作阶段。 */
  public ProjectStateCode requireRecover(ProjectStateCode blockedFromState) {
    ProjectWorkflowState stage = workflow.require(blockedFromState);
    ProjectStateCode blocked = ProjectWorkflowReservedState.BLOCKED.code();
    ProjectStateCode done = ProjectWorkflowReservedState.DONE.code();
    if (blocked.equals(stage.state()) || done.equals(stage.state()) || !stage.enabled()) {
      throw new IllegalArgumentException(
          "state " + blockedFromState + " cannot be a blocked recovery target");
    }
    return stage.state();
  }

  /** DONE 重开：只能由显式重开操作回到 INIT。 */
  public ProjectStateCode requireReopen(ProjectStateCode from) {
    if (from == null || !ProjectWorkflowReservedState.DONE.code().equals(from)) {
      throw new IllegalArgumentException("state " + from + " cannot be reopened");
    }
    return ProjectWorkflowReservedState.INIT.code();
  }
}
