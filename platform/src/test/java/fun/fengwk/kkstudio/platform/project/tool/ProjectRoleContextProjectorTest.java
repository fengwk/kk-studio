package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectRoleContextProjector} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 null 线程与未绑定属主的线程返回 {@code Optional.empty()}，不伪造上下文；
 *   <li>验证只注入可信 Run 元数据（issue_id、run_id、role、agent_name）与读取入口，Issue/Project 正文绝不进入
 *       systemInstruction；
 *   <li>验证 EXECUTOR 与 REVIEWER 各自得到正确的动作指引：执行者知道如何收尾，审查者知道必须调用 {@code issue_review}；
 *   <li>验证角色越权指引不出现：执行者上下文不得宣讲 {@code issue_review}。
 * </ul>
 */
class ProjectRoleContextProjectorTest {

  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

  private ProjectThreadOwnerResolver ownerResolver;
  private ProjectRoleContextProjector projector;

  @BeforeEach
  void setUp() {
    ownerResolver = mock(ProjectThreadOwnerResolver.class);
    projector = new ProjectRoleContextProjector(ownerResolver);
  }

  @Test
  void project_nullOrUnownedThread_returnsEmpty() {
    // 非 Issue Agent Branch（null 或没有活动 Run）不得注入任何 Issue Agent 上下文。
    assertTrue(projector.project(null).isEmpty());

    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.empty());
    assertTrue(projector.project(THREAD_ID).isEmpty());
  }

  @Test
  void project_executor_exposesTrustedRunMetadataAndReadEntry() {
    // 执行者上下文只有可信 Run 元数据与读取入口，并要求以最终答复收尾。
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner(ProjectRole.EXECUTOR)));

    String context = projector.project(THREAD_ID).orElseThrow();

    assertTrue(context.contains("- issue_id: " + ISSUE_ID), context);
    assertTrue(context.contains("- run_id: " + RUN_ID), context);
    assertTrue(context.contains("- role: EXECUTOR"), context);
    assertTrue(context.contains("- agent_name: executor-agent"), context);
    assertTrue(context.contains("`issue_read`"), context);
    assertTrue(context.contains("`issue_request_input`"), context);
    assertTrue(context.contains("final response"), context);
    assertFalse(context.contains("`issue_review`"), context);
  }

  @Test
  void project_reviewer_exposesDecisionDirective() {
    // 审查者必须被明确要求提交正式决定，否则 Issue 只能等人处理。
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner(ProjectRole.REVIEWER)));

    String context = projector.project(THREAD_ID).orElseThrow();

    assertTrue(context.contains("- role: REVIEWER"), context);
    assertTrue(context.contains("- agent_name: reviewer-agent"), context);
    assertTrue(context.contains("`issue_read`"), context);
    assertTrue(context.contains("`issue_review`"), context);
  }

  @Test
  void project_injectsNoIssueOrProjectBody() {
    // 只有固定元数据行 + Directives 段：可变的业务正文只能由 issue_read 作为业务输入提供。
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner(ProjectRole.EXECUTOR)));

    String context = projector.project(THREAD_ID).orElseThrow();

    assertFalse(context.contains("## Data"), context);
    assertFalse(context.contains("description"), context);
    long metadataLines =
        context
            .lines()
            .filter(line -> line.startsWith("- issue_") || line.startsWith("- run_"))
            .count();
    assertTrue(metadataLines == 2L, context);
  }

  private static ProjectThreadOwnerContext owner(ProjectRole role) {
    return new ProjectThreadOwnerContext(
        role,
        PROJECT_ID,
        ISSUE_ID,
        RUN_ID,
        role == ProjectRole.EXECUTOR ? "executor-agent" : "reviewer-agent");
  }
}
