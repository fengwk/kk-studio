package fun.fengwk.kkstudio.project.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;

import java.util.Optional;
import java.util.UUID;

class DatabaseProjectThreadOwnerResolverTest {
  private final IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
  private final IssueThreadExecution execution = mock(IssueThreadExecution.class);
  private final DatabaseProjectThreadOwnerResolver resolver =
      new DatabaseProjectThreadOwnerResolver(bindings, execution);

  /** 未知外部 Thread 不属于 Issue；其他读取错误不能被误判为无归属。 */
  @Test
  void missingThreadIsFalseButOtherErrorsPropagate() {
    UUID threadId = UUID.randomUUID();
    when(execution.findThreadSessionId(threadId)).thenReturn(Optional.empty());
    assertFalse(resolver.isIssueAgentBranch(threadId));

    IllegalStateException other = new IllegalStateException("store unavailable");
    doThrow(other).when(execution).findThreadSessionId(threadId);
    assertSame(
        other,
        assertThrows(IllegalStateException.class, () -> resolver.isIssueAgentBranch(threadId)));
  }
}
