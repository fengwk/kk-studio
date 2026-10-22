package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.platform.orchestration.IssueAgentOwnership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 人工交互统一写入口 {@link InteractionService} 的单元测试。 */
class InteractionServiceTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private IssueAgentOwnership issueAgentOwnership;
  private ObjectProvider<IssueAgentOwnership> issueAgentOwnerships;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    issueAgentOwnership = mock(IssueAgentOwnership.class);
    issueAgentOwnerships = mock(ObjectProvider.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(issueAgentOwnerships.getIfAvailable()).thenReturn(issueAgentOwnership);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    // 默认：任意存在的 Thread 自身即执行根；后代/不存在场景在具体测试中覆盖该 stub。
    when(runtime.findRootThreadId(any()))
        .thenAnswer(invocation -> Optional.of(invocation.<UUID>getArgument(0)));
    service = new InteractionService(issueAgentOwnerships, runtimes);
  }

  /** 测试意图：人工回答先锁定执行根的产品层级，再进入 Runtime。 */
  @Test
  void submitInputLocksRootProductScopeBeforeRuntime() {
    UUID threadId = id(100);
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            threadId, id(101), id(102), "alice", false, List.of(List.of("ans")));
    ToolInputAcceptance expected = mock(ToolInputAcceptance.class);
    when(runtime.submitToolInput(command)).thenReturn(expected);

    assertSame(expected, service.submitInput(command));

    InOrder order = inOrder(runtime, issueAgentOwnership);
    order.verify(runtime).findRootThreadId(threadId);
    order.verify(issueAgentOwnership).lockRootForInteraction(threadId);
    order.verify(runtime).submitToolInput(command);
  }

  /** 测试意图：工具审批与人工回答共用同一产品锁序，审批不能绕过 Issue 的层级串行化。 */
  @Test
  void decideApprovalLocksRootProductScopeBeforeRuntime() {
    UUID threadId = id(100);
    ToolApprovalCommand command =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.DENIED, id(102), "alice", "rejected");
    ToolInvocation expected = mock(ToolInvocation.class);
    when(runtime.decideToolApproval(command)).thenReturn(expected);

    assertSame(expected, service.decideApproval(command));

    InOrder order = inOrder(runtime, issueAgentOwnership);
    order.verify(issueAgentOwnership).lockRootForInteraction(threadId);
    order.verify(runtime).decideToolApproval(command);
  }

  /** 测试意图：后代任务按真实执行根加产品锁；写回的目标仍是原始子 Thread，不被根替代。 */
  @Test
  void submitInputOnDescendantLocksRootProductScope() {
    UUID childThreadId = id(100);
    UUID rootThreadId = id(101);
    when(runtime.findRootThreadId(childThreadId)).thenReturn(Optional.of(rootThreadId));
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            childThreadId, id(102), id(103), "alice", false, List.of(List.of("ans")));

    service.submitInput(command);

    InOrder order = inOrder(issueAgentOwnership, runtime);
    order.verify(issueAgentOwnership).lockRootForInteraction(rootThreadId);
    order.verify(runtime).submitToolInput(command);
  }

  /** 测试意图：产品层级事实损坏时 fail closed，回答与审批都不进入 Runtime。 */
  @Test
  void productScopeFailureNeverReachesRuntime() {
    UUID threadId = id(100);
    doThrow(new IllegalStateException("Issue owner does not exist"))
        .when(issueAgentOwnership)
        .lockRootForInteraction(threadId);

    IllegalStateException submitFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.submitInput(
                    new ToolInputSubmissionCommand(
                        threadId, id(101), id(102), "alice", true, List.of())));
    assertEquals("Issue owner does not exist", submitFailure.getMessage());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.decideApproval(
                new ToolApprovalCommand(
                    threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "ok")));

    verify(runtime, never()).submitToolInput(any());
    verify(runtime, never()).decideToolApproval(any());
  }

  /** 测试意图：没有 Project 应用时不存在 Issue+Agent 执行根，写入直接进入 Runtime。 */
  @Test
  void withoutProjectApplicationWritesGoStraightToRuntime() {
    when(issueAgentOwnerships.getIfAvailable()).thenReturn(null);
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(id(100), id(101), id(102), "alice", true, List.of());

    service.submitInput(command);

    verify(runtime).submitToolInput(command);
    verifyNoInteractions(issueAgentOwnership);
  }

  /** 测试意图：来源 Thread 不存在（祖先链为空）时共用根解析显式抛 not-found，绝不静默回退为自身，也不进入产品锁与 Runtime。 */
  @Test
  void missingThreadFailsWithNotFoundBeforeProductScopeAndRuntime() {
    UUID threadId = id(999);
    when(runtime.findRootThreadId(threadId)).thenReturn(Optional.empty());

    ToolInputSubmissionCommand submit =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());
    assertThrows(HarnessRuntimeNotFoundException.class, () -> service.submitInput(submit));

    ToolApprovalCommand approval =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "ok");
    assertThrows(HarnessRuntimeNotFoundException.class, () -> service.decideApproval(approval));

    verifyNoInteractions(issueAgentOwnership);
    verify(runtime, never()).submitToolInput(any());
    verify(runtime, never()).decideToolApproval(any());
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
    assertThrows(NullPointerException.class, () -> new InteractionService(null, runtimes));
    assertThrows(
        NullPointerException.class, () -> new InteractionService(issueAgentOwnerships, null));
  }
}
