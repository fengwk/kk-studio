package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;

import java.util.Objects;
import java.util.Set;

/**
 * 注册 Issue Run 冻结上下文与交接工具的 Harness Contributor。
 *
 * <p>{@code issue_transition} 是 SELECTABLE 工具：由 Issue Agent 的配置显式声明，运行时不再按 Thread owner 注入。上下文由
 * {@link ProjectRunContextProjector} 从本 contributor 自己的 {@code run} custom state 投影，因此普通 Thread
 * 不受影响， 也不存在跨 Thread 泄漏。
 */
public final class ProjectHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId(ProjectRunScope.CONTRIBUTOR_ID);
  public static final String NAME = "Project";
  public static final String VERSION = "1";

  /** Contributor 内 custom entry type 与 context projector 的稳定本地贡献名。 */
  private static final String LOCAL_ENTRY_TYPE = "issue.run";

  private static final String LOCAL_RUN_CONTEXT_PROJECTOR = "issue.run.context";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final IssueTransitionTool issueTransitionTool;

  public ProjectHarnessContributor(IssueTransitionTool issueTransitionTool) {
    this.issueTransitionTool = Objects.requireNonNull(issueTransitionTool, "issueTransitionTool");
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");
    registrar.registerCustomEntryType(LOCAL_ENTRY_TYPE, ProjectRunScope.CUSTOM_TYPE);
    registrar.registerContextProjector(
        LOCAL_RUN_CONTEXT_PROJECTOR, new ProjectRunContextProjector());
    registrar.registerTool(
        IssueTransitionTool.LOCAL_NAME, issueTransitionTool, ToolVisibility.SELECTABLE, 0);
  }
}
