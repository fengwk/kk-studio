package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 人工交互统一事务写入口 {@link InteractionService} 的单元测试。 */
class InteractionServiceTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(runtime.findRootThreadId(any()))
        .thenAnswer(invocation -> Optional.of(invocation.<UUID>getArgument(0)));
    service = new InteractionService(runtimes);
  }

  /** 输入在同一入口验证 Thread 并交由 Runtime 处理。 */
  @Test
  void submitInputValidatesThreadAndDelegates() {
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            id(100), id(101), id(102), "alice", false, List.of(List.of("ans")));
    ToolInputAcceptance expected = mock(ToolInputAcceptance.class);
    when(runtime.submitToolInput(command)).thenReturn(expected);

    assertSame(expected, service.submitInput(command));

    verify(runtime).findRootThreadId(id(100));
    verify(runtime).submitToolInput(command);
  }

  /** 审批在同一入口验证 Thread 并交由 Runtime 处理。 */
  @Test
  void decideApprovalValidatesThreadAndDelegates() {
    ToolApprovalCommand command =
        new ToolApprovalCommand(
            id(100), id(101), ToolApprovalDecision.DENIED, id(102), "alice", "rejected");
    ToolInvocation expected = mock(ToolInvocation.class);
    when(runtime.decideToolApproval(command)).thenReturn(expected);

    assertSame(expected, service.decideApproval(command));

    verify(runtime).findRootThreadId(id(100));
    verify(runtime).decideToolApproval(command);
  }

  /** 不存在目标 Thread 时回答与审批都在写入 Runtime 前失败。 */
  @Test
  void missingThreadFailsBeforeWrites() {
    UUID threadId = id(999);
    when(runtime.findRootThreadId(threadId)).thenReturn(Optional.empty());

    ToolInputSubmissionCommand submit =
        new ToolInputSubmissionCommand(threadId, id(101), id(102), "alice", true, List.of());
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(DomainException.class, () -> service.submitInput(submit)).code());

    ToolApprovalCommand approval =
        new ToolApprovalCommand(
            threadId, id(101), ToolApprovalDecision.ALLOWED, id(102), "alice", "ok");
    assertThrows(DomainException.class, () -> service.decideApproval(approval));

    verify(runtime, never()).submitToolInput(any());
    verify(runtime, never()).decideToolApproval(any());
  }
}
