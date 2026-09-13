package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectRoleToolSelector} 及 {@link ProjectRoleToolIds} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 Coordinator(9)、Executor(2)、Reviewer(1) 角色工具 ID 的精确数量与声明顺序；
 *   <li>验证角色工具集合及其返回结果在任何情况下均为严格不可变列表；
 *   <li>验证 null 角色防护与构造参数防御性校验；
 *   <li>验证根据反查的所有权上下文正确投影出相应的工具集合；
 *   <li>验证未解析或不存在的所有权返回空不可变列表。
 * </ul>
 */
class ProjectRoleToolSelectorTest {

  private static final UUID THREAD_ID = id(1);
  private static final UUID PROJECT_ID = id(2);
  private static final UUID ISSUE_ID = id(3);
  private static final UUID RUN_ID = id(4);

  private ProjectThreadOwnerResolver ownerResolver;
  private ProjectRoleToolSelector selector;

  @BeforeEach
  void setUp() {
    ownerResolver = mock(ProjectThreadOwnerResolver.class);
    selector = new ProjectRoleToolSelector(ownerResolver);
  }

  @Test
  void exactRoleToolIdsAndOrder() {
    // 验证 Coordinator 精确包含 9 个内部工具且顺序严格对齐设计
    List<AgentToolId> expectedCoordinator =
        List.of(
            new AgentToolId("project.read"),
            new AgentToolId("issue.read"),
            new AgentToolId("issue.list"),
            new AgentToolId("issue.create"),
            new AgentToolId("issue.update"),
            new AgentToolId("issue.add-dependency"),
            new AgentToolId("issue.remove-dependency"),
            new AgentToolId("issue.set-status"),
            new AgentToolId("issue.cancel"));
    assertEquals(9, ProjectRoleToolIds.COORDINATOR.size());
    assertEquals(expectedCoordinator, ProjectRoleToolIds.COORDINATOR);

    // 验证 Executor 精确包含 2 个内部工具且顺序严格对齐设计
    List<AgentToolId> expectedExecutor =
        List.of(new AgentToolId("issue.submit"), new AgentToolId("issue.request-input"));
    assertEquals(2, ProjectRoleToolIds.EXECUTOR.size());
    assertEquals(expectedExecutor, ProjectRoleToolIds.EXECUTOR);

    // 验证 Reviewer 精确包含 1 个内部工具且顺序严格对齐设计
    List<AgentToolId> expectedReviewer = List.of(new AgentToolId("issue.review"));
    assertEquals(1, ProjectRoleToolIds.REVIEWER.size());
    assertEquals(expectedReviewer, ProjectRoleToolIds.REVIEWER);
  }

  @Test
  void forRole_success() {
    // 验证 forRole 工具方法对所有角色枚举映射的正确性
    assertEquals(
        ProjectRoleToolIds.COORDINATOR, ProjectRoleToolIds.forRole(ProjectRole.COORDINATOR));
    assertEquals(ProjectRoleToolIds.EXECUTOR, ProjectRoleToolIds.forRole(ProjectRole.EXECUTOR));
    assertEquals(ProjectRoleToolIds.REVIEWER, ProjectRoleToolIds.forRole(ProjectRole.REVIEWER));
  }

  @Test
  void forRole_null_throwsNpe() {
    // 验证 forRole 传入 null 时抛出明确的 NullPointerException
    NullPointerException ex =
        assertThrows(NullPointerException.class, () -> ProjectRoleToolIds.forRole(null));
    assertTrue(ex.getMessage().contains("role"));
  }

  @Test
  void roleToolListsAreImmutable() {
    // 验证常量角色工具列表的不可变性
    AgentToolId extraTool = new AgentToolId("extra.tool");
    assertThrows(
        UnsupportedOperationException.class, () -> ProjectRoleToolIds.COORDINATOR.add(extraTool));
    assertThrows(
        UnsupportedOperationException.class, () -> ProjectRoleToolIds.COORDINATOR.remove(0));
    assertThrows(UnsupportedOperationException.class, ProjectRoleToolIds.COORDINATOR::clear);

    assertThrows(
        UnsupportedOperationException.class, () -> ProjectRoleToolIds.EXECUTOR.add(extraTool));
    assertThrows(UnsupportedOperationException.class, () -> ProjectRoleToolIds.EXECUTOR.remove(0));
    assertThrows(UnsupportedOperationException.class, ProjectRoleToolIds.EXECUTOR::clear);

    assertThrows(
        UnsupportedOperationException.class, () -> ProjectRoleToolIds.REVIEWER.add(extraTool));
    assertThrows(UnsupportedOperationException.class, () -> ProjectRoleToolIds.REVIEWER.remove(0));
    assertThrows(UnsupportedOperationException.class, ProjectRoleToolIds.REVIEWER::clear);
  }

  @Test
  void select_coordinator_returnsImmutable9Tools() {
    // 验证 Coordinator 角色线程选出 9 个工具且返回结果不可修改
    when(ownerResolver.resolve(THREAD_ID))
        .thenReturn(
            Optional.of(
                new ProjectThreadOwnerContext(
                    ProjectRole.COORDINATOR, PROJECT_ID, null, null, "coordinator-agent")));

    List<AgentToolId> tools = selector.select(THREAD_ID);

    assertEquals(9, tools.size());
    assertEquals(ProjectRoleToolIds.COORDINATOR, tools);
    assertThrows(
        UnsupportedOperationException.class, () -> tools.add(new AgentToolId("unsupported")));
  }

  @Test
  void select_executor_returnsImmutable2Tools() {
    // 验证 Executor 角色线程选出 2 个工具且返回结果不可修改
    when(ownerResolver.resolve(THREAD_ID))
        .thenReturn(
            Optional.of(
                new ProjectThreadOwnerContext(
                    ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent")));

    List<AgentToolId> tools = selector.select(THREAD_ID);

    assertEquals(2, tools.size());
    assertEquals(ProjectRoleToolIds.EXECUTOR, tools);
    assertThrows(
        UnsupportedOperationException.class, () -> tools.add(new AgentToolId("unsupported")));
  }

  @Test
  void select_reviewer_returnsImmutable1Tool() {
    // 验证 Reviewer 角色线程选出 1 个工具且返回结果不可修改
    when(ownerResolver.resolve(THREAD_ID))
        .thenReturn(
            Optional.of(
                new ProjectThreadOwnerContext(
                    ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, RUN_ID, "reviewer-agent")));

    List<AgentToolId> tools = selector.select(THREAD_ID);

    assertEquals(1, tools.size());
    assertEquals(ProjectRoleToolIds.REVIEWER, tools);
    assertThrows(
        UnsupportedOperationException.class, () -> tools.add(new AgentToolId("unsupported")));
  }

  @Test
  void select_missingOrUnownedThread_returnsImmutableEmptyList() {
    // 验证未解析或不存在所有权的线程返回空不可变列表
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.empty());

    List<AgentToolId> tools = selector.select(THREAD_ID);

    assertTrue(tools.isEmpty());
    assertThrows(
        UnsupportedOperationException.class, () -> tools.add(new AgentToolId("unsupported")));
  }

  @Test
  void constructor_nullCheck() {
    // 验证 selector 构造函数防空校验
    assertThrows(NullPointerException.class, () -> new ProjectRoleToolSelector(null));
  }

  @Test
  void projectRole_enumCoverage() {
    // 验证 ProjectRole 枚举完整性
    assertEquals(ProjectRole.COORDINATOR, ProjectRole.valueOf("COORDINATOR"));
    assertEquals(ProjectRole.EXECUTOR, ProjectRole.valueOf("EXECUTOR"));
    assertEquals(ProjectRole.REVIEWER, ProjectRole.valueOf("REVIEWER"));
    assertEquals(3, ProjectRole.values().length);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
