package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueWork;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue 调度邮箱（{@code project_issue_work}）的 wake/lease 围栏：重试与旧 Worker 都不能吞掉新唤醒。
 *
 * <p>测试意图：唤醒版本单调递增且 {@code due_at} 取最早时间，领取只对到期且未租用的行生效，续租与完成都以 lease token 与新唤醒版本 为条件——旧 claim
 * 完成失败时行必须保留，新唤醒不能被合并进旧 claim。
 */
class IssueWorkStoreIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueWorkStore issueWorkStore;

  /** 唤醒请求递增版本并保留最早的到期时间：同一 Issue 的多次唤醒是「重新检查」而不是并行任务。 */
  @Test
  void requestWorkBumpsWakeVersionAndKeepsEarliestDueAt() {
    UUID issueId = newIssueId();
    Instant now = Instant.now();

    IssueWork first = issueWorkStore.requestWork(issueId, now.plusSeconds(10));
    IssueWork second = issueWorkStore.requestWork(issueId, now.plusSeconds(2));

    assertEquals(1L, first.getWakeVersion());
    assertEquals(2L, second.getWakeVersion());
    assertEquals(second.getDueAt(), issueWorkStore.getWork(issueId).getDueAt());
    assertTrue(!second.getDueAt().isAfter(now.plusSeconds(2)));
    assertNull(second.getLeaseToken());
    assertNull(second.getLeaseUntil());
    // 未到期的工作不能被领取。
    assertTrue(issueWorkStore.claimNext(now, key("lease"), now.plusSeconds(30)).isEmpty());
  }

  /** 领取只对到期且未租用的行生效；续租必须以原 token 且延长租期。 */
  @Test
  void claimLeasesDueWorkAndRenewLeaseFencesByToken() {
    UUID issueId = newIssueId();
    Instant now = Instant.now();
    issueWorkStore.requestWork(issueId, now.minusSeconds(1));
    String leaseToken = key("lease");

    Optional<IssueWork> claimed = issueWorkStore.claimNext(now, leaseToken, now.plusSeconds(30));

    assertTrue(claimed.isPresent());
    assertEquals(1L, claimed.get().getWakeVersion());
    assertEquals(leaseToken, claimed.get().getLeaseToken());
    assertNotNull(claimed.get().getLeaseUntil());
    // 已租用的行不会被第二次领取。
    assertTrue(
        issueWorkStore.claimNext(now.plusSeconds(1), key("other"), now.plusSeconds(60)).isEmpty());
    assertThrows(
        AiValidationException.class,
        () -> issueWorkStore.renewLease(issueId, key("other"), now, now.plusSeconds(120)));
    assertThrows(
        AiValidationException.class,
        () -> issueWorkStore.renewLease(issueId, leaseToken, now, now.plusSeconds(10)));

    issueWorkStore.renewLease(issueId, leaseToken, now, now.plusSeconds(120));

    assertEquals(
        claimed.get().getLeaseUntil().plusSeconds(90),
        issueWorkStore.getWork(issueId).getLeaseUntil());
  }

  /** 新唤醒到达后，旧 claim 既不能完成工作也不能吞掉新唤醒；重新领取到新版本才能安全完成。 */
  @Test
  void staleClaimCannotCompleteAndNewWakeSurvives() {
    UUID issueId = newIssueId();
    Instant now = Instant.now();
    issueWorkStore.requestWork(issueId, now.minusSeconds(1));
    String firstToken = key("lease");
    IssueWork claimed =
        issueWorkStore.claimNext(now, firstToken, now.plusSeconds(30)).orElseThrow();
    issueWorkStore.requestWork(issueId, now.plusSeconds(5));

    assertThrows(
        AiValidationException.class,
        () -> issueWorkStore.completeWork(issueId, firstToken, claimed.getWakeVersion(), now));

    IssueWork survived = issueWorkStore.getWork(issueId);
    assertEquals(2L, survived.getWakeVersion());
    // 过期租用被重新领取后按新唤醒版本完成，行才被删除。
    Instant later = now.plusSeconds(600);
    String secondToken = key("lease");
    IssueWork reclaimed =
        issueWorkStore.claimNext(later, secondToken, later.plusSeconds(30)).orElseThrow();
    assertEquals(2L, reclaimed.getWakeVersion());
    issueWorkStore.completeWork(issueId, secondToken, reclaimed.getWakeVersion(), later);

    assertThrows(AiResourceNotFoundException.class, () -> issueWorkStore.getWork(issueId));
    assertTrue(issueWorkStore.claimNext(later, key("lease"), later.plusSeconds(30)).isEmpty());
  }

  /** lease 输入按契约校验：租期必须晚于当前时间，token 非空白。 */
  @Test
  void claimRejectsInvalidLeaseInput() {
    UUID issueId = newIssueId();
    Instant now = Instant.now();

    assertThrows(
        AiValidationException.class, () -> issueWorkStore.claimNext(now, key("lease"), now));
    assertThrows(
        AiValidationException.class, () -> issueWorkStore.claimNext(now, " ", now.plusSeconds(1)));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueWorkStore.renewLease(issueId, key("lease"), now, now.plusSeconds(30)));
  }

  private UUID newIssueId() {
    Issue issue = createIssue(createProject());
    return issue.getId();
  }
}
