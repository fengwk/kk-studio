package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 人工交互统一写入口 {@link InteractionService} 的单元测试。 */
class InteractionServiceTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private IssueAgentThreadRepository issueAgentThreadRepository;
  private IssueRepository issueRepository;
  private ProjectRepository projectRepository;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    issueRepository = mock(IssueRepository.class);
    projectRepository = mock(ProjectRepository.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service =
        new InteractionService(
            issueAgentThreadRepository, issueRepository, projectRepository, runtimes);
  }

  /**
   * 测试意图：验证当 Thread 命中 Issue+Agent 绑定时，submitInput 严格按照 Project SHARE (lockForKeyShare) -> Issue
   * UPDATE (lockById) 锁序加锁，且锁获取必须在调用 runtime.submitToolInput 之前完成。
   */
  @Test
  void submitInputWithIssueAgentBindingLocksProjectThenIssueBeforeRuntime() {
    UUID threadId = id(100);
    UUID issueId = id(200);
    UUID projectId = id(300);

    IssueAgentThread binding = new IssueAgentThread(issueId, "coder", threadId);
    when(issueAgentThreadRepository.findByThreadId(threadId)).thenReturn(binding);

    Issue initialIssue = Issue.builder().id(issueId).projectId(projectId).build();
    when(issueRepository.getById(issueId)).thenReturn(initialIssue);

    Project project = mock(Project.class);
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(project);

    Issue lockedIssue = Issue.builder().id(issueId).projectId(projectId).build();
    when(issueRepository.lockById(issueId)).thenReturn(lockedIssue);

    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            threadId, id(101), id(102), "alice", false, List.of(List.of("ans")));
    ToolInputAcceptance expectedAcceptance = mock(ToolInputAcceptance.class);
    when(runtime.submitToolInput(command)).thenReturn(expectedAcceptance);

    ToolInputAcceptance actual = service.submitInput(command);

    assertSame(expectedAcceptance, actual);

    InOrder inOrder = inOrder(issueRepository, projectRepository, runtime);
    inOrder.verify(issueRepository).getById(issueId);
    inOrder.verify(projectRepository).lockForKeyShare(projectId);
    inOrder.verify(issueRepository).lockById(issueId);
    inOrder.verify(runtime).submitToolInput(command);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * 测试意图：验证当 Thread 命中 Issue+Agent 绑定时，decideApproval 严格按照 Project SHARE (lockForKeyShare) -> Issue
   * UPDATE (lockById) 锁序加锁，且锁获取必须在调用 runtime.decideToolApproval 之前完成。
   */
  @Test
  void decideApprovalWithIssueAgentBindingLocksProjectThenIssueBeforeRuntime() {
    UUID threadId = id(100);
    UUID issueId = id(200);
    UUID projectId = id(300);

    IssueAgentThread binding = new IssueAgentThread(issueId, "reviewer", threadId);
    when(issueAgentThreadRepository.findByThreadId(threadId)).thenReturn(binding);

    Issue initialIssue = Issue.builder().id(issueId).projectId(projectId).build();
    when(issueRepository.getById(issueId)).thenReturn(initialIssue);

    Project project = mock(Project.class);
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(project);

    Issue lockedIssue = Issue.builder().id(issueId).projectId(projectId).build();
    when(issueRepository.lockById(issueId)).thenReturn(lockedIssue);

    ToolApprovalCommand command =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "approved");
    ToolInvocation expectedInvocation = mock(ToolInvocation.class);
    when(runtime.decideToolApproval(command)).thenReturn(expectedInvocation);

    ToolInvocation actual = service.decideApproval(command);

    assertSame(expectedInvocation, actual);

    InOrder inOrder = inOrder(issueRepository, projectRepository, runtime);
    inOrder.verify(issueRepository).getById(issueId);
    inOrder.verify(projectRepository).lockForKeyShare(projectId);
    inOrder.verify(issueRepository).lockById(issueId);
    inOrder.verify(runtime).decideToolApproval(command);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * 测试意图：验证对于 Chat 或内部委派 Thread（无 IssueAgentThread 绑定），submitInput 直接透传至
   * runtime.submitToolInput，绝不触碰 ProjectRepository 或 IssueRepository。
   */
  @Test
  void submitInputWithoutBindingDirectlyCallsRuntimeWithoutProductRepositoryInteractions() {
    UUID threadId = id(100);
    when(issueAgentThreadRepository.findByThreadId(threadId)).thenReturn(null);

    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());
    ToolInputAcceptance expectedAcceptance = mock(ToolInputAcceptance.class);
    when(runtime.submitToolInput(command)).thenReturn(expectedAcceptance);

    ToolInputAcceptance actual = service.submitInput(command);

    assertSame(expectedAcceptance, actual);
    verify(runtime).submitToolInput(command);
    verifyNoInteractions(projectRepository, issueRepository);
  }

  /**
   * 测试意图：验证对于 Chat 或内部委派 Thread（无 IssueAgentThread 绑定），decideApproval 直接透传至
   * runtime.decideToolApproval，绝不触碰 ProjectRepository 或 IssueRepository。
   */
  @Test
  void decideApprovalWithoutBindingDirectlyCallsRuntimeWithoutProductRepositoryInteractions() {
    UUID threadId = id(100);
    when(issueAgentThreadRepository.findByThreadId(threadId)).thenReturn(null);

    ToolApprovalCommand command =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.DENIED, id(102), "alice", "rejected");
    ToolInvocation expectedInvocation = mock(ToolInvocation.class);
    when(runtime.decideToolApproval(command)).thenReturn(expectedInvocation);

    ToolInvocation actual = service.decideApproval(command);

    assertSame(expectedInvocation, actual);
    verify(runtime).decideToolApproval(command);
    verifyNoInteractions(projectRepository, issueRepository);
  }

  /**
   * 测试意图：验证当 Issue 处于控制暂停（isPaused() == true）或归档（isArchived() == true）状态时，InteractionService
   * 仅负责外层产品串行化加锁，不校验也不阻止写路径，人工回答与工具审批照常转发至 runtime 并成功返回。
   */
  @Test
  void pausedOrArchivedIssueStateDoesNotBlockWritePath() {
    UUID threadId = id(100);
    UUID issueId = id(200);
    UUID projectId = id(300);

    IssueAgentThread binding = new IssueAgentThread(issueId, "coder", threadId);
    when(issueAgentThreadRepository.findByThreadId(threadId)).thenReturn(binding);

    // 构造同时处于暂停 (pauseReason != null) 与归档 (archivedAt != null) 的 Issue
    Issue pausedAndArchivedIssue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .pauseReason("USER")
            .pauseDetail("User paused execution for maintenance")
            .archivedAt(Instant.parse("2026-03-01T12:00:00Z"))
            .build();
    assertTrue(pausedAndArchivedIssue.isPaused());
    assertTrue(pausedAndArchivedIssue.isArchived());

    when(issueRepository.getById(issueId)).thenReturn(pausedAndArchivedIssue);
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(mock(Project.class));
    when(issueRepository.lockById(issueId)).thenReturn(pausedAndArchivedIssue);

    // 验证 submitInput 照常提交并转发
    ToolInputSubmissionCommand inputCommand =
        new ToolInputSubmissionCommand(
            threadId, id(101), id(102), "alice", false, List.of(List.of("ans")));
    ToolInputAcceptance expectedAcceptance = mock(ToolInputAcceptance.class);
    when(runtime.submitToolInput(inputCommand)).thenReturn(expectedAcceptance);

    ToolInputAcceptance actualAcceptance = service.submitInput(inputCommand);
    assertSame(expectedAcceptance, actualAcceptance);
    verify(runtime).submitToolInput(inputCommand);

    // 验证 decideApproval 照常决策并转发
    ToolApprovalCommand approvalCommand =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "ok");
    ToolInvocation expectedInvocation = mock(ToolInvocation.class);
    when(runtime.decideToolApproval(approvalCommand)).thenReturn(expectedInvocation);

    ToolInvocation actualInvocation = service.decideApproval(approvalCommand);
    assertSame(expectedInvocation, actualInvocation);
    verify(runtime).decideToolApproval(approvalCommand);
  }

  /**
   * 测试意图：验证当存在 IssueAgent 绑定但 issueRepository.getById 返回 null 时，判定为归属事实损坏，抛出
   * IllegalStateException，且绝不进入 runtime。
   */
  @Test
  void missingIssueRowThrowsIllegalStateExceptionAndNeverCallsRuntime() {
    UUID threadId = id(100);
    UUID issueId = id(200);

    when(issueAgentThreadRepository.findByThreadId(threadId))
        .thenReturn(new IssueAgentThread(issueId, "coder", threadId));
    when(issueRepository.getById(issueId)).thenReturn(null);

    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.submitInput(command));
    assertEquals("Issue owner does not exist", exception.getMessage());

    verifyNoInteractions(projectRepository, runtime);
  }

  /**
   * 测试意图：验证当存在 IssueAgent 绑定且 Issue 存在，但 projectRepository.lockForKeyShare 返回 null 时，判定为归属事实损坏，抛出
   * IllegalStateException，且绝不调用 issueRepository.lockById 与 runtime。
   */
  @Test
  void missingProjectRowThrowsIllegalStateExceptionAndNeverCallsRuntime() {
    UUID threadId = id(100);
    UUID issueId = id(200);
    UUID projectId = id(300);

    when(issueAgentThreadRepository.findByThreadId(threadId))
        .thenReturn(new IssueAgentThread(issueId, "coder", threadId));
    when(issueRepository.getById(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(null);

    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.submitInput(command));
    assertEquals("Project owner does not exist", exception.getMessage());

    verify(issueRepository, never()).lockById(any());
    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：验证加行锁阶段如果 Issue 行缺失或锁定后的 projectId 与前次读取不一致，判定为层级事实损坏，抛出 IllegalStateException，且绝不调用
   * runtime。
   */
  @Test
  void inconsistentHierarchyOnLockThrowsIllegalStateExceptionAndNeverCallsRuntime() {
    UUID threadId = id(100);
    UUID issueId = id(200);
    UUID projectId = id(300);

    when(issueAgentThreadRepository.findByThreadId(threadId))
        .thenReturn(new IssueAgentThread(issueId, "coder", threadId));
    when(issueRepository.getById(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(mock(Project.class));

    // 分支 1：lockById 返回 null
    when(issueRepository.lockById(issueId)).thenReturn(null);

    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());

    IllegalStateException exception1 =
        assertThrows(IllegalStateException.class, () -> service.submitInput(command));
    assertEquals("Issue owner hierarchy is inconsistent", exception1.getMessage());
    verifyNoInteractions(runtime);

    // 分支 2：lockById 返回的 Issue 其 projectId 与前次读取不一致
    UUID differentProjectId = id(999);
    when(issueRepository.lockById(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(differentProjectId).build());

    IllegalStateException exception2 =
        assertThrows(IllegalStateException.class, () -> service.submitInput(command));
    assertEquals("Issue owner hierarchy is inconsistent", exception2.getMessage());
    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 decideApproval 在产品层级事实损坏时同样 fail closed 抛出 IllegalStateException 且不调用 runtime。 */
  @Test
  void decideApprovalFailsClosedWhenProductScopeFails() {
    UUID threadId = id(100);
    UUID issueId = id(200);

    when(issueAgentThreadRepository.findByThreadId(threadId))
        .thenReturn(new IssueAgentThread(issueId, "reviewer", threadId));
    when(issueRepository.getById(issueId)).thenReturn(null);

    ToolApprovalCommand command =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "ok");

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.decideApproval(command));
    assertEquals("Issue owner does not exist", exception.getMessage());
    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 HarnessRuntime 未就绪时抛出 IllegalStateException。 */
  @Test
  void runtimeUnavailableThrowsIllegalStateException() {
    when(runtimes.getIfAvailable()).thenReturn(null);
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(id(100), id(101), id(102), "alice", true, List.of());
    assertThrows(IllegalStateException.class, () -> service.submitInput(command));
  }

  /** 测试意图：验证入参为 null 时的非空校验。 */
  @Test
  void nullCommandThrowsNullPointerException() {
    assertThrows(NullPointerException.class, () -> service.submitInput(null));
    assertThrows(NullPointerException.class, () -> service.decideApproval(null));
  }

  /** 测试意图：验证构造函数的非空防御。 */
  @Test
  void constructorRejectsNullDependencies() {
    assertThrows(
        NullPointerException.class,
        () -> new InteractionService(null, issueRepository, projectRepository, runtimes));
    assertThrows(
        NullPointerException.class,
        () ->
            new InteractionService(issueAgentThreadRepository, null, projectRepository, runtimes));
    assertThrows(
        NullPointerException.class,
        () -> new InteractionService(issueAgentThreadRepository, issueRepository, null, runtimes));
    assertThrows(
        NullPointerException.class,
        () ->
            new InteractionService(
                issueAgentThreadRepository, issueRepository, projectRepository, null));
  }
}
