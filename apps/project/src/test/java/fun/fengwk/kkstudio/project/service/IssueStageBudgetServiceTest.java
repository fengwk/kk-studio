package fun.fengwk.kkstudio.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;

import java.util.UUID;

/** Stage budget 的客户端 state 在触达持久化前完成领域编码校验。 */
class IssueStageBudgetServiceTest {

  private final IssueStageBudgetRepository stageBudgets = mock(IssueStageBudgetRepository.class);
  private final IssueRunRepository issueRuns = mock(IssueRunRepository.class);
  private final IssueMutationSupport mutationSupport = mock(IssueMutationSupport.class);
  private final IssueStageBudgetService service =
      new IssueStageBudgetService(stageBudgets, issueRuns, mutationSupport);

  @Test
  void rejectsMissingStateBeforeReadingIssueFacts() {
    assertMissingState(
        () -> service.authorizeStageBudget(UUID.randomUUID(), 0L, "request", null, 1));
    assertMissingState(() -> service.resetStageBudget(UUID.randomUUID(), 0L, "request", null, 1));
    verifyNoInteractions(stageBudgets, issueRuns, mutationSupport);
  }

  private static void assertMissingState(Runnable action) {
    DomainException error = assertThrows(DomainException.class, action::run);
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals("state must be a state code", error.getMessage());
  }
}
