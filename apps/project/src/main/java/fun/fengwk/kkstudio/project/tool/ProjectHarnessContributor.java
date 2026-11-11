package fun.fengwk.kkstudio.project.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Objects;
import java.util.Set;

/**
 * 注册 Issue 交接工具的 Harness Contributor。
 *
 * <p>{@code issue_transition} 是 SELECTABLE 工具：由 Issue Agent 的配置显式声明，运行时不按 Thread owner 隐式注入。
 *
 * <p>Issue 运行上下文由接受 Run 时的 Markdown 任务消息承载，交接授权只来自 Project 自己的 Run 记录。
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
        IssueTransitionTool.LOCAL_NAME, issueTransitionTool, ToolVisibility.SELECTABLE, 0);
  }
}
