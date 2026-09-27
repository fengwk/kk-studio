package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Objects;
import java.util.Set;

/**
 * 注册 Issue Agent 交接工具的 Harness Contributor。
 *
 * <p>{@code issue_transition} 是 INTERNAL 工具：Agent 不能把它声明进自己的工具面（Agent 配置校验只接受 SELECTABLE 工具），只有
 * DatabaseTurnResolver 在 Issue Agent Thread 的 Turn 上按稳定归属注入它。本 Contributor 不注册 context
 * projector：Issue/阶段上下文必须按具体 Thread 解析，而 context projector 只看到 branch 历史，无法区分"哪个 Issue Agent
 * Thread"，因此上下文由 Turn 解析器直接注入。
 */
public final class ProjectHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("project");
  public static final String NAME = "Project";
  public static final String VERSION = "1";

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
    registrar.registerTool(
        IssueTransitionTool.LOCAL_NAME, issueTransitionTool, ToolVisibility.INTERNAL, 0);
  }
}
