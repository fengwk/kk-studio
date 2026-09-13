package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@link ProjectThreadOwnerResolver} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 Coordinator、Executor、Reviewer 三种角色的 Harness Thread 属主解析链路；
 *   <li>验证缺失 Thread 或未绑定任何 Project/Run 的 Session 返回 Optional.empty()；
 *   <li>验证 ProjectSession 与 IssueRunSession 双重认领的歧义场景被坚决拒绝；
 *   <li>验证关联实体缺失、外键/Session 映射不一致等场景抛出脱敏异常；
 *   <li>验证 HUMAN 类型的 Run 以及 null 角色被严格拦截拒绝；
 *   <li>验证所有异常信息均为通用描述且绝不回显任何 UUID；
 *   <li>验证 ProjectThreadOwnerContext 的不可变属性与结构约束。
 * </ul>
 */
class ProjectThreadOwnerResolverTest {

  private static final UUID THREAD_ID = id(1);
  private static final UUID SESSION_ID = id(2);
  private static final UUID PROJECT_ID = id(3);
  private static final UUID ISSUE_ID = id(4);
  private static final UUID RUN_ID = id(5);
  private static final UUID OTHER_ID = id(99);
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private HarnessStore harnessStore;
  private HarnessStore.Transaction transaction;
  private ProjectSessionRepository projectSessionRepository;
  private IssueRunSessionRepository issueRunSessionRepository;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueRunRepository issueRunRepository;
  private ProjectThreadOwnerResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    harnessStore = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    projectSessionRepository = mock(ProjectSessionRepository.class);
    issueRunSessionRepository = mock(IssueRunSessionRepository.class);
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueRunRepository = mock(IssueRunRepository.class);

    when(harnessStore.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });

    resolver =
        new ProjectThreadOwnerResolver(
            harnessStore,
            projectSessionRepository,
            issueRunSessionRepository,
            projectRepository,
            issueRepository,
            issueRunRepository);
  }

  @Test
  void resolve_coordinator_success() {
    // 验证 Coordinator 关联的 Session 正确反查出 ProjectRole.COORDINATOR 角色上下文。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, SESSION_ID, NOW));
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(
            Project.builder().id(PROJECT_ID).coordinatorAgentName("coordinator-agent").build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.COORDINATOR, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertNull(context.get().issueId());
    assertNull(context.get().runId());
    assertEquals("coordinator-agent", context.get().agentName());
  }

  @Test
  void resolve_executor_success() {
    // 验证 Executor 关联的 Session 正确反查出 ProjectRole.EXECUTOR 角色上下文。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.EXECUTOR, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertEquals(ISSUE_ID, context.get().issueId());
    assertEquals(RUN_ID, context.get().runId());
    assertEquals("coder-agent", context.get().agentName());
  }

  @Test
  void resolve_reviewer_success() {
    // 验证 Reviewer 关联的 Session 正确反查出 ProjectRole.REVIEWER 角色上下文。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.REVIEWER)
                .actorType(IssueRunActorType.AGENT)
                .agentName("reviewer-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.REVIEWER, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertEquals(ISSUE_ID, context.get().issueId());
    assertEquals(RUN_ID, context.get().runId());
    assertEquals("reviewer-agent", context.get().agentName());
  }

  @Test
  void resolve_missingThread_returnsEmpty() {
    // 验证底层 Harness 存储中不存在 Thread 时返回 Optional.empty()。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.empty());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_unownedSession_returnsEmpty() {
    // 验证 Session 未被 ProjectSession 或 IssueRunSession 认领时返回 Optional.empty()。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID)).thenReturn(null);
    when(issueRunSessionRepository.findBySessionId(SESSION_ID)).thenReturn(null);

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_ambiguousProjectAndRunRelation_throwsInconsistent() {
    // 验证当 Session 同时匹配到 ProjectSession 和 IssueRunSession 时判定为数据不一致并绝不泄露 UUID。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, SESSION_ID, NOW));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_coordinatorSessionIdMismatch_throwsInconsistent() {
    // 验证 ProjectSession 的 sessionId 与 Thread 的 sessionId 不一致时抛出异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, OTHER_ID, NOW));

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_coordinatorProjectNotFound_throwsInconsistent() {
    // 验证 ProjectSession 关联的 Project 不存在时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, SESSION_ID, NOW));
    when(projectRepository.getById(PROJECT_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_coordinatorProjectIdMismatch_throwsInconsistent() {
    // 验证 Project 的 ID 与 ProjectSession 的 projectId 不一致时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, SESSION_ID, NOW));
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(
            Project.builder().id(OTHER_ID).coordinatorAgentName("coordinator-agent").build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_coordinatorAgentNameNullOrBlank_throwsInconsistent() {
    // 验证 Project 的 coordinatorAgentName 为 null 或全空白时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(projectSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new ProjectSession(PROJECT_ID, SESSION_ID, NOW));

    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).coordinatorAgentName(null).build());
    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));

    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).coordinatorAgentName("   ").build());
    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunSessionIdMismatch_throwsInconsistent() {
    // 验证 IssueRunSession 的 sessionId 与 Thread 的 sessionId 不一致时抛出异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, OTHER_ID, NOW));

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunNotFound_throwsInconsistent() {
    // 验证 IssueRunSession 关联的 IssueRun 不存在时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunIdMismatch_throwsInconsistent() {
    // 验证 IssueRun 的 ID 与 IssueRunSession 的 runId 不一致时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(OTHER_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunAgentNameNullOrBlank_throwsInconsistent() {
    // 验证 IssueRun 的 agentName 为 null 或全空白时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));

    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName(null)
                .build());
    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));

    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("   ")
                .build());
    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueNotFound_throwsInconsistent() {
    // 验证 IssueRun 关联的 Issue 不存在时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueIdMismatch_throwsInconsistent() {
    // 验证 Issue 的 ID 与 IssueRun 的 issueId 不一致时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(OTHER_ID).projectId(PROJECT_ID).build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunProjectNotFound_throwsInconsistent() {
    // 验证 Issue 关联的 Project 不存在时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueRunProjectIdMismatch_throwsInconsistent() {
    // 验证 Project 的 ID 与 Issue 的 projectId 不一致时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID)).thenReturn(Project.builder().id(OTHER_ID).build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_humanRunRejection_throwsInconsistent() {
    // 验证 HUMAN 类型的 IssueRun 被严格拒绝作为 Agent 工具所有权来源。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.HUMAN)
                .agentName("human-operator")
                .build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_nullRunRole_throwsInconsistent() {
    // 验证 IssueRun 的 role 为 null 时抛出一致性异常。
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueRunSessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(new IssueRunSession(RUN_ID, SESSION_ID, NOW));
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(null)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .build());
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_nullThreadId_throwsNpe() {
    // 验证 threadId 传 null 时防御性抛出 NullPointerException。
    assertThrows(NullPointerException.class, () -> resolver.resolve(null));
  }

  @Test
  void constructor_nullChecks() {
    // 验证构造函数各个关键依赖不可为 null。
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                null,
                projectSessionRepository,
                issueRunSessionRepository,
                projectRepository,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                null,
                issueRunSessionRepository,
                projectRepository,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                projectSessionRepository,
                null,
                projectRepository,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                projectSessionRepository,
                issueRunSessionRepository,
                null,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                projectSessionRepository,
                issueRunSessionRepository,
                projectRepository,
                null,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                projectSessionRepository,
                issueRunSessionRepository,
                projectRepository,
                issueRepository,
                null));
  }

  @Test
  void projectThreadOwnerContext_validation() {
    // 验证 ProjectThreadOwnerContext 的参数校验与不变量约束。
    assertThrows(
        NullPointerException.class,
        () -> new ProjectThreadOwnerContext(null, PROJECT_ID, null, null, "agent"));
    assertThrows(
        NullPointerException.class,
        () -> new ProjectThreadOwnerContext(ProjectRole.COORDINATOR, null, null, null, "agent"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectThreadOwnerContext(ProjectRole.COORDINATOR, PROJECT_ID, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectThreadOwnerContext(ProjectRole.COORDINATOR, PROJECT_ID, null, null, "  "));

    // Coordinator 不得携带 issueId 或 runId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.COORDINATOR, PROJECT_ID, ISSUE_ID, null, "agent"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.COORDINATOR, PROJECT_ID, null, RUN_ID, "agent"));

    // Executor / Reviewer 必须同时携带 issueId 与 runId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(ProjectRole.EXECUTOR, PROJECT_ID, null, RUN_ID, "agent"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, null, "agent"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(ProjectRole.REVIEWER, PROJECT_ID, null, RUN_ID, "agent"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, null, "agent"));
  }

  private void assertInconsistentOwnership(Executable executable) {
    IllegalStateException ex = assertThrows(IllegalStateException.class, executable);
    assertEquals("Project thread ownership is inconsistent", ex.getMessage());
    // 关键安全约束：通用异常信息绝不得拼接任何 UUID 事实
    assertFalse(ex.getMessage().contains(THREAD_ID.toString()));
    assertFalse(ex.getMessage().contains(SESSION_ID.toString()));
    assertFalse(ex.getMessage().contains(PROJECT_ID.toString()));
    assertFalse(ex.getMessage().contains(ISSUE_ID.toString()));
    assertFalse(ex.getMessage().contains(RUN_ID.toString()));
    assertFalse(
        ex.getMessage()
            .matches(
                ".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"));
  }

  private static ThreadState thread(UUID threadId, UUID sessionId) {
    return new ThreadState(
        threadId, sessionId, id(100), "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
