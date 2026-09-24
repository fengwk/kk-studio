package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;

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
 *   <li>验证 Executor、Reviewer 两种角色的 Harness Thread 属主解析链路（直接通过 IssueAgentSession.threadId 或通过
 *       HarnessStore.findThread -&gt; sessionId 回查）；
 *   <li>验证缺失 Thread 或未绑定 IssueAgentSession 的 Session 返回 Optional.empty()；
 *   <li>验证无活动 Run、活动 Run 已终结或活动 Run 的 agentName 不匹配时返回 Optional.empty()；
 *   <li>验证关联实体缺失（Issue 或 Project 不存在）、ID 映射不一致等场景抛出通用脱敏异常；
 *   <li>验证所有异常信息均为通用描述且绝不回显任何 UUID；
 *   <li>验证 withStoreSupplier 工厂方法与 Supplier 返回 null 时的 fail-closed 语义；
 *   <li>验证防空校验与 ProjectThreadOwnerContext 的不可变属性与结构约束。
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
  private IssueAgentSessionRepository issueAgentSessionRepository;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueRunRepository issueRunRepository;
  private ProjectThreadOwnerResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    harnessStore = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    issueAgentSessionRepository = mock(IssueAgentSessionRepository.class);
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
            issueAgentSessionRepository,
            projectRepository,
            issueRepository,
            issueRunRepository);
  }

  @Test
  void resolve_byThreadId_executor_success() {
    // 验证通过 threadId 直接命中 IssueAgentSession 并反查出 EXECUTOR 上下文
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(10))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_PROGRESS)
                .build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.RUNNING)
                .build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.EXECUTOR, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertEquals(ISSUE_ID, context.get().issueId());
    assertEquals(RUN_ID, context.get().runId());
    assertEquals("coder-agent", context.get().agentName());
  }

  @Test
  void resolve_byThreadId_reviewer_success() {
    // 验证通过 threadId 直接命中 IssueAgentSession 并反查出 REVIEWER 上下文
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(11))
            .issueId(ISSUE_ID)
            .agentName("reviewer-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_REVIEW)
                .build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.REVIEWER)
                .agentName("reviewer-agent")
                .status(IssueRunStatus.RUNNING)
                .build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.REVIEWER, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertEquals(ISSUE_ID, context.get().issueId());
    assertEquals(RUN_ID, context.get().runId());
    assertEquals("reviewer-agent", context.get().agentName());
  }

  @Test
  void resolve_fallbackToHarnessStore_sessionId_success() {
    // 验证 findByThreadId 为空时回退到 HarnessStore 查 sessionId 再命中 IssueAgentSession
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(12))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findBySessionId(SESSION_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_PROGRESS)
                .build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.WAITING_HUMAN)
                .build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.EXECUTOR, context.get().role());
    assertEquals(PROJECT_ID, context.get().projectId());
    assertEquals(ISSUE_ID, context.get().issueId());
    assertEquals(RUN_ID, context.get().runId());
    assertEquals("coder-agent", context.get().agentName());
  }

  @Test
  void resolve_missingThreadAndSession_returnsEmpty() {
    // 验证 threadId 未直接绑定且 HarnessStore 中不存在该 Thread 时返回 Optional.empty()
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.empty());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_storeThreadFoundButSessionUnbound_returnsEmpty() {
    // 验证 HarnessStore 中有 Thread 但 sessionId 未被 IssueAgentSession 认领时返回 Optional.empty()
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    when(issueAgentSessionRepository.findBySessionId(SESSION_ID)).thenReturn(null);

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_withStoreSupplier_nullStore_fallsClosedToEmpty() {
    // 验证 StoreSupplier 返回 null 时 fail-closed 返回 Optional.empty()
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(null);
    ProjectThreadOwnerResolver supplierResolver =
        ProjectThreadOwnerResolver.withStoreSupplier(
            () -> null,
            issueAgentSessionRepository,
            projectRepository,
            issueRepository,
            issueRunRepository);

    Optional<ProjectThreadOwnerContext> context = supplierResolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_withStoreSupplier_activeStore_resolvesSuccessfully() {
    // 验证 withStoreSupplier 在提供可用 Store 时能够正确解析
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(null);
    when(transaction.findThread(THREAD_ID)).thenReturn(Optional.of(thread(THREAD_ID, SESSION_ID)));
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(13))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findBySessionId(SESSION_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.RUNNING)
                .build());

    ProjectThreadOwnerResolver supplierResolver =
        ProjectThreadOwnerResolver.withStoreSupplier(
            () -> harnessStore,
            issueAgentSessionRepository,
            projectRepository,
            issueRepository,
            issueRunRepository);

    Optional<ProjectThreadOwnerContext> context = supplierResolver.resolve(THREAD_ID);

    assertTrue(context.isPresent());
    assertEquals(ProjectRole.EXECUTOR, context.get().role());
  }

  @Test
  void resolve_noActiveRun_returnsEmpty() {
    // 验证没有活动 IssueRun 时返回 Optional.empty()
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(14))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID)).thenReturn(null);

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_inactiveRun_returnsEmpty() {
    // 验证找到的 Run 处于已完成/非活跃状态时返回 Optional.empty()
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(15))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.COMPLETED)
                .build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_runAgentNameMismatch_returnsEmpty() {
    // 验证活动 Run 的 agentName 与 IssueAgentSession 不匹配时返回 Optional.empty()
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(16))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRunRepository.findActiveByIssueId(ISSUE_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("other-agent")
                .status(IssueRunStatus.RUNNING)
                .build());

    Optional<ProjectThreadOwnerContext> context = resolver.resolve(THREAD_ID);

    assertTrue(context.isEmpty());
  }

  @Test
  void resolve_issueNotFound_throwsInconsistent() {
    // 验证 IssueAgentSession 关联的 Issue 不存在时抛出一致性异常
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(17))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_issueIdMismatch_throwsInconsistent() {
    // 验证 Issue 仓库返回的 ID 与 agentSession.issueId 不匹配时抛出一致性异常
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(18))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(OTHER_ID).projectId(PROJECT_ID).build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_projectNotFound_throwsInconsistent() {
    // 验证 Issue 关联的 Project 不存在时抛出一致性异常
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(19))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID)).thenReturn(null);

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_projectIdMismatch_throwsInconsistent() {
    // 验证 Project 仓库返回的 ID 与 issue.projectId 不匹配时抛出一致性异常
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(id(20))
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByThreadId(THREAD_ID)).thenReturn(agentSession);
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).build());
    when(projectRepository.getById(PROJECT_ID)).thenReturn(Project.builder().id(OTHER_ID).build());

    assertInconsistentOwnership(() -> resolver.resolve(THREAD_ID));
  }

  @Test
  void resolve_nullThreadId_throwsNpe() {
    // 验证 threadId 传 null 时防御性抛出 NullPointerException
    NullPointerException ex =
        assertThrows(NullPointerException.class, () -> resolver.resolve(null));
    assertTrue(ex.getMessage().contains("threadId"));
  }

  @Test
  void constructor_nullChecks() {
    // 验证构造函数各个关键依赖不可为 null
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                null,
                issueAgentSessionRepository,
                projectRepository,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore, null, projectRepository, issueRepository, issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                issueAgentSessionRepository,
                null,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                issueAgentSessionRepository,
                projectRepository,
                null,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerResolver(
                harnessStore,
                issueAgentSessionRepository,
                projectRepository,
                issueRepository,
                null));
  }

  @Test
  void withStoreSupplier_nullChecks() {
    // 验证 withStoreSupplier 工厂方法各个关键依赖不可为 null
    assertThrows(
        NullPointerException.class,
        () ->
            ProjectThreadOwnerResolver.withStoreSupplier(
                null,
                issueAgentSessionRepository,
                projectRepository,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            ProjectThreadOwnerResolver.withStoreSupplier(
                () -> harnessStore, null, projectRepository, issueRepository, issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            ProjectThreadOwnerResolver.withStoreSupplier(
                () -> harnessStore,
                issueAgentSessionRepository,
                null,
                issueRepository,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            ProjectThreadOwnerResolver.withStoreSupplier(
                () -> harnessStore,
                issueAgentSessionRepository,
                projectRepository,
                null,
                issueRunRepository));
    assertThrows(
        NullPointerException.class,
        () ->
            ProjectThreadOwnerResolver.withStoreSupplier(
                () -> harnessStore,
                issueAgentSessionRepository,
                projectRepository,
                issueRepository,
                null));
  }

  @Test
  void projectThreadOwnerContext_validation() {
    // 验证 ProjectThreadOwnerContext 的参数校验与不变量约束
    assertThrows(
        NullPointerException.class,
        () -> new ProjectThreadOwnerContext(null, PROJECT_ID, ISSUE_ID, RUN_ID, "agent"));
    assertThrows(
        NullPointerException.class,
        () -> new ProjectThreadOwnerContext(ProjectRole.EXECUTOR, null, ISSUE_ID, RUN_ID, "agent"));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerContext(ProjectRole.EXECUTOR, PROJECT_ID, null, RUN_ID, "agent"));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, null, "agent"));
    assertThrows(
        NullPointerException.class,
        () ->
            new ProjectThreadOwnerContext(
                ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, null));
  }

  @Test
  void projectThreadOwnerContext_recordInvariants() {
    // 验证 ProjectThreadOwnerContext 的访问器与不可变 record 契约
    ProjectThreadOwnerContext context =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    assertEquals(ProjectRole.EXECUTOR, context.role());
    assertEquals(PROJECT_ID, context.projectId());
    assertEquals(ISSUE_ID, context.issueId());
    assertEquals(RUN_ID, context.runId());
    assertEquals("coder-agent", context.agentName());

    ProjectThreadOwnerContext same =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    assertEquals(context, same);
    assertEquals(context.hashCode(), same.hashCode());
    assertTrue(context.toString().contains("coder-agent"));
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
