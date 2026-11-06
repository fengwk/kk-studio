package fun.fengwk.kkstudio.project.service;

import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertDuplicate;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;

import java.util.UUID;
import java.util.function.Supplier;

class IssueActivityIdempotencyTest {

  private final IssueActivityRepository repository = mock(IssueActivityRepository.class);
  private final UUID issueId = UUID.randomUUID();
  private final Identity identity =
      IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "TEST", "key");

  @Test
  void preLockReceiptAvoidsLock() {
    // 锁前命中不能调用加锁过程。
    IssueActivity activity = IssueActivity.builder().requestHash(identity.requestHash()).build();
    when(repository.findByIdempotencyKey(issueId, identity.key())).thenReturn(activity);
    Supplier<UUID> lock = mock(Supplier.class);

    var result =
        IssueActivityIdempotency.lockUnlessReplayed(repository, issueId, identity, lock, id -> id);
    assertNull(result.locked());
    assertSame(activity, result.activity());
    verify(lock, never()).get();
  }

  @Test
  void lockedReceiptUsesLockedIssueId() {
    // 首次检查后出现的 receipt 必须按锁内 Issue 再查。
    UUID lockedId = UUID.randomUUID();
    IssueActivity activity = IssueActivity.builder().requestHash(identity.requestHash()).build();
    when(repository.findByIdempotencyKey(lockedId, identity.key())).thenReturn(activity);
    var result =
        IssueActivityIdempotency.lockUnlessReplayed(
            repository, issueId, identity, () -> lockedId, id -> id);
    assertSame(lockedId, result.locked());
    assertSame(activity, result.activity());
    verify(repository).findByIdempotencyKey(issueId, identity.key());
    verify(repository).findByIdempotencyKey(lockedId, identity.key());
  }

  @Test
  void missAndConflictUnderLock() {
    // 锁内未命中允许首次执行，异指纹则不能继续。
    UUID lockedId = UUID.randomUUID();
    var result =
        IssueActivityIdempotency.lockUnlessReplayed(
            repository, issueId, identity, () -> lockedId, id -> id);
    assertSame(lockedId, result.locked());
    assertNull(result.activity());
    when(repository.findByIdempotencyKey(lockedId, identity.key()))
        .thenReturn(IssueActivity.builder().requestHash("different").build());
    assertDuplicate(
        () ->
            IssueActivityIdempotency.lockUnlessReplayed(
                repository, issueId, identity, () -> lockedId, id -> id));
  }
}
