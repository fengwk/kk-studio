package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.project.controller.IssueControllerProperties;
import fun.fengwk.kkstudio.project.controller.IssueReconciler;
import fun.fengwk.kkstudio.project.controller.IssueReconciler.IssueWorkClaim;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 真实 PostgreSQL 的调度延迟、权威时间和 token/wake 围栏回归；通过 SQL 过期租约，不 sleep。 */
class IssueWorkStoreIntegrationTest extends ProjectTestSupport {

  private static final Duration LEASE = Duration.ofMinutes(1);

  @Autowired private IssueWorkStore issueWorkStore;

  /** 已到期 work 重排必须等待 delay；这是旧 least(due_at, new_due) 的红灯复现。 */
  @Test
  void rescheduleDelaysAlreadyDueWork() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork claimed = claim("retry");
    assertTrue(
        issueWorkStore.rescheduleWork(
            issueId, "retry", claimed.getWakeVersion(), Duration.ofSeconds(30)));
    IssueWork delayed = issueWorkStore.getWork(issueId);
    assertEquals(delayed.getUpdatedAt().plusSeconds(30), delayed.getDueAt());
    assertNull(delayed.getLeaseToken());
    assertNull(delayed.getLeaseUntil());
    assertTrue(issueWorkStore.claimNext("next", LEASE).isEmpty());

    // 不等待墙钟：强制 due 已到期，验证到期后仍能再次领取；ZERO 不引入额外延迟。
    jdbc.update(
        "update project_issue_work set due_at = statement_timestamp() - interval '1 second' where issue_id = ?",
        issueId);
    IssueWork next = claim("next");
    assertTrue(
        issueWorkStore.rescheduleWork(issueId, "next", next.getWakeVersion(), Duration.ZERO));
    IssueWork immediate = issueWorkStore.getWork(issueId);
    assertEquals(immediate.getUpdatedAt(), immediate.getDueAt());
    assertEquals(issueId, claim("zero").getIssueId());
  }

  /** 多次唤醒递增版本并合并最早 due，未来 work 不应被领取。 */
  @Test
  void requestWorkBumpsWakeVersionAndKeepsEarliestDueAt() {
    UUID issueId = newIssueId();
    IssueWork first = issueWorkStore.requestWork(issueId, Duration.ofMinutes(10));
    IssueWork second = issueWorkStore.requestWork(issueId, Duration.ofSeconds(2));
    IssueWork third = issueWorkStore.requestWork(issueId, Duration.ofMinutes(20));
    assertEquals(1L, first.getWakeVersion());
    assertEquals(2L, second.getWakeVersion());
    assertEquals(3L, third.getWakeVersion());
    assertEquals(second.getCreatedAt(), first.getCreatedAt());
    assertEquals(second.getUpdatedAt().plusSeconds(2), second.getDueAt());
    assertEquals(second.getDueAt(), third.getDueAt());
    assertEquals(third.getDueAt(), issueWorkStore.getWork(issueId).getDueAt());
    assertNull(second.getLeaseToken());
    assertNull(second.getLeaseUntil());
    assertTrue(issueWorkStore.claimNext("future", LEASE).isEmpty());
  }

  /** 续租由 DB 判定有效性，较短 duration 不缩短已有租期，较长 duration 延长租期。 */
  @Test
  void claimLeasesDueWorkAndRenewLeaseFencesByToken() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork claimed = claim("lease");
    assertEquals(1L, claimed.getWakeVersion());
    assertEquals("lease", claimed.getLeaseToken());
    assertNotNull(claimed.getLeaseUntil());
    assertEquals(claimed.getUpdatedAt().plus(LEASE), claimed.getLeaseUntil());
    assertTrue(issueWorkStore.claimNext("other", LEASE).isEmpty());
    assertThrows(
        ProjectValidationException.class, () -> issueWorkStore.renewLease(issueId, "other", LEASE));
    issueWorkStore.renewLease(issueId, "lease", Duration.ofSeconds(1));
    assertEquals(claimed.getLeaseUntil(), issueWorkStore.getWork(issueId).getLeaseUntil());
    issueWorkStore.renewLease(issueId, "lease", Duration.ofMinutes(2));
    IssueWork renewed = issueWorkStore.getWork(issueId);
    assertEquals(renewed.getUpdatedAt().plusSeconds(120), renewed.getLeaseUntil());
    assertTrue(renewed.getLeaseUntil().isAfter(claimed.getLeaseUntil()));
  }

  /** 新 wake 到达时保留正在处理的 lease；旧 claim 重排不得推迟新唤醒。 */
  @Test
  void newWakeIsNotPostponedByReschedule() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork claimed = claim("lease");
    IssueWork woken = issueWorkStore.requestWork(issueId, Duration.ZERO);
    assertEquals("lease", woken.getLeaseToken());
    assertEquals(claimed.getLeaseUntil(), woken.getLeaseUntil());
    assertTrue(
        issueWorkStore.rescheduleWork(
            issueId, "lease", claimed.getWakeVersion(), Duration.ofMinutes(10)));
    assertEquals(woken.getDueAt(), issueWorkStore.getWork(issueId).getDueAt());
    assertEquals(woken.getWakeVersion(), claim("next").getWakeVersion());
  }

  /** 新 wake 不能被旧 complete 删除；它必须释放 lease 并可立即重领新版本。 */
  @Test
  void staleClaimCannotCompleteAndNewWakeSurvives() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork claimed = claim("first");
    issueWorkStore.requestWork(issueId, Duration.ofMinutes(5));
    assertFalse(issueWorkStore.completeWork(issueId, "first", claimed.getWakeVersion()));
    IssueWork survived = issueWorkStore.getWork(issueId);
    assertEquals(2L, survived.getWakeVersion());
    assertNull(survived.getLeaseToken());
    assertNull(survived.getLeaseUntil());
    IssueWork reclaimed = claim("second");
    assertEquals(2L, reclaimed.getWakeVersion());
    assertTrue(issueWorkStore.completeWork(issueId, "second", reclaimed.getWakeVersion()));
    assertThrows(ProjectNotFoundException.class, () -> issueWorkStore.getWork(issueId));
    assertTrue(issueWorkStore.claimNext("empty", LEASE).isEmpty());
  }

  /** 过期 claim 与被接管的旧 token 对所有写操作均无效，且失败不得修改新租约或 due。 */
  @Test
  void expiredAndReplacedTokensCannotWrite() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork first = claim("first");
    expire(issueId);
    IssueWork expired = issueWorkStore.getWork(issueId);
    assertRejectedWrites(issueId, "first", first.getWakeVersion());
    assertEquals(expired, issueWorkStore.getWork(issueId));
    IssueWork second = claim("second");
    assertRejectedWrites(issueId, "first", first.getWakeVersion());
    assertEquals(second, issueWorkStore.getWork(issueId));
    assertTrue(issueWorkStore.completeWork(issueId, "second", second.getWakeVersion()));
  }

  /** 输入校验在写库之前拒绝 null/negative delay、无效租期和 token；失败不能创建或改变 work。 */
  @Test
  void claimRejectsInvalidLeaseInput() {
    UUID issueId = newIssueId();
    assertThrows(NullPointerException.class, () -> issueWorkStore.requestWork(issueId, null));
    assertThrows(
        ProjectValidationException.class,
        () -> issueWorkStore.requestWork(issueId, Duration.ofNanos(-1)));
    assertThrows(ProjectNotFoundException.class, () -> issueWorkStore.getWork(issueId));
    for (Duration duration : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1))) {
      assertThrows(
          ProjectValidationException.class, () -> issueWorkStore.claimNext("lease", duration));
      assertThrows(
          ProjectValidationException.class,
          () -> issueWorkStore.renewLease(issueId, "lease", duration));
    }
    assertThrows(NullPointerException.class, () -> issueWorkStore.claimNext("lease", null));
    for (String token : List.of("", " ", "x".repeat(129))) {
      assertThrows(ProjectValidationException.class, () -> issueWorkStore.claimNext(token, LEASE));
    }
    assertThrows(ProjectValidationException.class, () -> issueWorkStore.claimNext(null, LEASE));
    assertThrows(
        ProjectNotFoundException.class, () -> issueWorkStore.renewLease(issueId, "lease", LEASE));
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueWork claimed = claim("lease");
    assertThrows(
        NullPointerException.class,
        () -> issueWorkStore.rescheduleWork(issueId, "lease", claimed.getWakeVersion(), null));
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueWorkStore.rescheduleWork(
                issueId, "lease", claimed.getWakeVersion(), Duration.ofNanos(-1)));
    assertEquals(claimed, issueWorkStore.getWork(issueId));
  }

  /** 两个真实事务同时 claim 同一个 due work，只能有一个成功且数据库记录唯一 owner。 */
  @Test
  void simultaneousClaimsAreExclusive() throws Exception {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> concurrentClaim("first", ready, start));
      var second = executor.submit(() -> concurrentClaim("second", ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      Optional<IssueWork> one = first.get(5, TimeUnit.SECONDS);
      Optional<IssueWork> two = second.get(5, TimeUnit.SECONDS);
      assertEquals(1, (one.isPresent() ? 1 : 0) + (two.isPresent() ? 1 : 0));
      assertEquals(one.orElseGet(two::orElseThrow), issueWorkStore.getWork(issueId));
    } finally {
      start.countDown();
    }
  }

  /** 不注入应用 Clock：deadline 来自真实 PostgreSQL，续租与失败重排以数据库时间加相对 Duration 写入。 */
  @Test
  void dispatcherUsesDatabaseLeaseAndRelativeRetryDelay() {
    UUID issueId = newIssueId();
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    IssueControllerProperties properties = new IssueControllerProperties();
    properties.setLeaseDuration(LEASE);
    AtomicReference<IssueWorkClaim> handedOff = new AtomicReference<>();
    IssueReconciler reconciler = mock(IssueReconciler.class);
    doAnswer(
            invocation -> {
              IssueWorkClaim claim = invocation.getArgument(0);
              handedOff.set(claim);
              IssueWork leased = issueWorkStore.getWork(issueId);
              assertEquals(leased.getLeaseUntil(), claim.leaseUntil());
              assertEquals(leased.getUpdatedAt().plus(LEASE), claim.leaseUntil());
              assertThrows(
                  ProjectValidationException.class,
                  () -> issueWorkStore.renewLease(issueId, "wrong", LEASE));
              issueWorkStore.renewLease(issueId, claim.leaseToken(), LEASE);
              IssueWork renewed = issueWorkStore.getWork(issueId);
              assertEquals(renewed.getUpdatedAt().plus(LEASE), renewed.getLeaseUntil());
              throw new IllegalStateException("retry");
            })
        .when(reconciler)
        .reconcile(any(IssueWorkClaim.class));
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    try (IssueControllerDispatcher dispatcher =
        new IssueControllerDispatcher(
            issueWorkStore, reconciler, properties, Runnable::run, Runnable::run, scheduler)) {
      dispatcher.start();
    }
    assertNotNull(handedOff.get());
    IssueWork delayed = issueWorkStore.getWork(issueId);
    assertNull(delayed.getLeaseToken());
    assertNull(delayed.getLeaseUntil());
    assertEquals(delayed.getUpdatedAt().plus(properties.getRetryDelay()), delayed.getDueAt());
    assertTrue(issueWorkStore.claimNext("early", LEASE).isEmpty());
  }

  private Optional<IssueWork> concurrentClaim(
      String token, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
    ready.countDown();
    assertTrue(start.await(5, TimeUnit.SECONDS));
    return issueWorkStore.claimNext(token, LEASE);
  }

  private void assertRejectedWrites(UUID issueId, String token, long wakeVersion) {
    assertFalse(issueWorkStore.rescheduleWork(issueId, token, wakeVersion, Duration.ZERO));
    assertFalse(issueWorkStore.completeWork(issueId, token, wakeVersion));
    assertThrows(
        ProjectValidationException.class, () -> issueWorkStore.renewLease(issueId, token, LEASE));
  }

  private void expire(UUID issueId) {
    jdbc.update(
        "update project_issue_work set lease_until = statement_timestamp() - interval '1 second' where issue_id = ?",
        issueId);
  }

  private IssueWork claim(String token) {
    return issueWorkStore.claimNext(token, LEASE).orElseThrow();
  }

  private UUID newIssueId() {
    return createIssue(createProject()).getId();
  }
}
