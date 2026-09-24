package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue Agent Branch 的系统上下文投影：只注入可信 Run 元数据与业务读取入口。
 *
 * <p>Issue/Project 正文只由 {@code issue_read} 作为业务输入提供，绝不提升为 systemInstruction：要求、验收依据、依赖与活动都可能被
 * 调用方或模型改写，放进系统指令会把可变业务数据伪装成不可协商的指令。这里只有由持久事实固定下来的 Issue ID、Run ID、职责与参与者身份。
 */
@Component
public class ProjectRoleContextProjector {

  private final ProjectThreadOwnerResolver ownerResolver;

  public ProjectRoleContextProjector(ProjectThreadOwnerResolver ownerResolver) {
    this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
  }

  /** 返回当前 Branch 的 Issue Agent 上下文；非 Issue Agent Branch 或没有活动 Run 时为空。 */
  @Transactional(readOnly = true)
  public Optional<String> project(UUID threadId) {
    if (threadId == null) {
      return Optional.empty();
    }
    return ownerResolver.resolve(threadId).map(this::render);
  }

  private String render(ProjectThreadOwnerContext owner) {
    return String.format(
        """
        # Issue Agent Context

        - issue_id: %s
        - run_id: %s
        - role: %s
        - agent_name: %s

        ## Directives
        %s
        """,
        owner.issueId(),
        owner.runId(),
        owner.role().name(),
        owner.agentName(),
        directives(owner.role()));
  }

  private static String directives(ProjectRole role) {
    return switch (role) {
      case EXECUTOR -> """
          - You are the issue executor for the bound run. Do the work this run is responsible for.
          - Call `issue_read` to load the current requirements, acceptance criteria, dependencies and activity before acting; never rely on earlier conversation.
          - Finish the run with a final response that states what was done and how it was verified.
          - Use `issue_request_input` only when you need information or a decision from a human.
          """;
      case REVIEWER -> """
          - You are the issue reviewer for the bound run. Judge the submission against the current requirements.
          - Call `issue_read` to load the current requirements, acceptance criteria, submission and activity before deciding; never rely on earlier conversation.
          - Call `issue_review` with `decision` and `reason`; not calling it leaves the issue waiting for a human.
          - Use `issue_request_input` only when you need information from a human.
          """;
    };
  }
}
