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
import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 验证 IssueControllerWorkStore 状态持久化、租约控制与并发争抢： 包含 Work 请求排队与 wakeVersion 递增、FOR UPDATE SKIP LOCKED
 * 抢占、租约续约与超时恢复、 重新调度与版本匹配完成。
 */
class IssueControllerWorkStoreIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueControllerWorkStore controllerWorkStore;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;

  @Test
  void testRequestWorkAndGetWork() {
    String agent = createTestAgent();
    Project project = projectService.createProject("WorkStore Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "WorkStore Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    // 初始无工作
    assertNull(controllerWorkStore.getWork(issueId));

    // 请求工作
    Instant dueAt = Instant.now().minusSeconds(10);
    IssueControllerWork requested = controllerWorkStore.requestWork(issueId, dueAt);
    assertNotNull(requested);
    assertEquals(issueId, requested.getIssueId());
    assertEquals(1L, requested.getWakeVersion());
    assertNull(requested.getLeaseToken());
    assertNull(requested.getLeaseUntil());

    IssueControllerWork fetched = controllerWorkStore.getWork(issueId);
    assertNotNull(fetched);
    assertEquals(issueId, fetched.getIssueId());
    assertEquals(1L, fetched.getWakeVersion());
  }

  @Test
  void testClaimNextAndLeaseTimeoutRecovery() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Claim Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Claim Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    Instant now = Instant.now();
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));

    // Worker 1 抢占工作，租约 30 秒
    String token1 = "worker-token-1";
    Instant lease1 = now.plusSeconds(30);
    Optional<ClaimedControllerWork> claimed1 = controllerWorkStore.claimNext(now, token1, lease1);
    assertTrue(claimed1.isPresent());
    assertEquals(issueId, claimed1.get().getIssueId());
    assertEquals(token1, claimed1.get().getLeaseToken());
    assertEquals(1L, claimed1.get().getClaimedWakeVersion());

    // 租约期间，Worker 2 在当前时间抢占应当返回 empty（被锁定）
    String token2 = "worker-token-2";
    Optional<ClaimedControllerWork> claimed2 =
        controllerWorkStore.claimNext(now.plusSeconds(5), token2, now.plusSeconds(35));
    assertTrue(claimed2.isEmpty());

    // 模拟时间推进到租约过期后（+35 秒），Worker 2 应当能重新抢占过期工作
    Instant futureNow = now.plusSeconds(35);
    Optional<ClaimedControllerWork> claimedAfterExpire =
        controllerWorkStore.claimNext(futureNow, token2, futureNow.plusSeconds(30));
    assertTrue(claimedAfterExpire.isPresent());
    assertEquals(issueId, claimedAfterExpire.get().getIssueId());
    assertEquals(token2, claimedAfterExpire.get().getLeaseToken());
  }

  @Test
  void testRenewLease() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Renew Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Renew Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    Instant now = Instant.now();
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));

    String token1 = "worker-token-1";
    Instant lease1 = now.plusSeconds(30);
    Optional<ClaimedControllerWork> claimed = controllerWorkStore.claimNext(now, token1, lease1);
    assertTrue(claimed.isPresent());

    // 错误的 leaseToken 续约抛出校验异常
    assertThrows(
        AiValidationException.class,
        () ->
            controllerWorkStore.renewLease(
                issueId, "wrong-token", now.plusSeconds(5), now.plusSeconds(60)));

    // 正确 leaseToken 续约成功
    Instant newLease = now.plusSeconds(60);
    controllerWorkStore.renewLease(issueId, token1, now.plusSeconds(5), newLease);

    IssueControllerWork updatedWork = controllerWorkStore.getWork(issueId);
    assertEquals(newLease.getEpochSecond(), updatedWork.getLeaseUntil().getEpochSecond());
  }

  @Test
  void testRescheduleWork() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Reschedule Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Reschedule Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    Instant now = Instant.now();
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));
    String token = "worker-token";
    Instant lease = now.plusSeconds(30);
    Optional<ClaimedControllerWork> claimed = controllerWorkStore.claimNext(now, token, lease);
    assertTrue(claimed.isPresent());

    // 重新调度至未来 60 秒（requestedAt = now + 60s）
    Instant futureDue = now.plusSeconds(60);
    controllerWorkStore.rescheduleWork(
        issueId, token, claimed.get().getClaimedWakeVersion(), now.plusSeconds(5), futureDue);

    // 此时租约已清除，但在 futureDue 之前 claimNext 无法抢占到
    Optional<ClaimedControllerWork> claimBeforeFuture =
        controllerWorkStore.claimNext(now.plusSeconds(10), "other-token", now.plusSeconds(40));
    assertTrue(claimBeforeFuture.isEmpty());

    // 在 futureDue 之后可以成功抢占
    Instant afterFuture = now.plusSeconds(65);
    Optional<ClaimedControllerWork> claimAfterFuture =
        controllerWorkStore.claimNext(afterFuture, "other-token", afterFuture.plusSeconds(30));
    assertTrue(claimAfterFuture.isPresent());
    assertEquals(issueId, claimAfterFuture.get().getIssueId());
  }

  @Test
  void testCompleteWorkWithWakeMatchingAndWakeNewer() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Complete Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Complete Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    Instant now = Instant.now();
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));

    String token = "worker-token";
    Instant lease = now.plusSeconds(30);
    Optional<ClaimedControllerWork> claimed = controllerWorkStore.claimNext(now, token, lease);
    assertTrue(claimed.isPresent());

    // 分支 1: claimedWakeVersion 匹配且执行期间无新 wake -> completeWork 删除该工作记录
    controllerWorkStore.completeWork(
        issueId, token, claimed.get().getClaimedWakeVersion(), now.plusSeconds(5));
    assertNull(controllerWorkStore.getWork(issueId));

    // 分支 2: 执行期间发生了新的 requestWork 导致 wakeVersion 增加
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));
    Optional<ClaimedControllerWork> claimed2 =
        controllerWorkStore.claimNext(now, token, now.plusSeconds(30));
    assertTrue(claimed2.isPresent());
    long oldWakeVersion = claimed2.get().getClaimedWakeVersion();

    // 在 worker 处理期间外部又请求了 work（例如产生了新输入），wakeVersion 递增
    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));
    IssueControllerWork current = controllerWorkStore.getWork(issueId);
    assertTrue(current.getWakeVersion() > oldWakeVersion);

    // 此时使用旧的 claimedWakeVersion completeWork，不会删除该记录，而是清除租约使新工作立即可被抢占
    controllerWorkStore.completeWork(issueId, token, oldWakeVersion, now.plusSeconds(5));
    IssueControllerWork clearedWork = controllerWorkStore.getWork(issueId);
    assertNotNull(clearedWork);
    assertNull(clearedWork.getLeaseToken());
    assertNull(clearedWork.getLeaseUntil());

    // 另一个 worker 能立即抢占该工作
    Optional<ClaimedControllerWork> reclaimed =
        controllerWorkStore.claimNext(now.plusSeconds(6), "new-worker", now.plusSeconds(36));
    assertTrue(reclaimed.isPresent());
    assertEquals(issueId, reclaimed.get().getIssueId());
  }

  @Test
  void testConcurrentClaimSkipLocked() throws InterruptedException {
    String agent = createTestAgent();
    Project project = projectService.createProject("Concurrent Claim Proj", "Desc", agent);

    // 创建 5 个任务并全部请求工作
    int issueCount = 5;
    for (int i = 0; i < issueCount; i++) {
      Issue issue =
          issueService.createIssue(
              project.getId(), "Task " + i, "Desc", agent, null, IssueStatus.BACKLOG);
      controllerWorkStore.requestWork(issue.getId(), Instant.now().minusSeconds(1));
    }

    // 5 个并发 Worker 同时争抢
    int workerCount = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(workerCount);

    Set<UUID> claimedIssues = Collections.synchronizedSet(new HashSet<>());
    ExecutorService executor = Executors.newFixedThreadPool(workerCount);
    Instant now = Instant.now();
    try {
      for (int w = 0; w < workerCount; w++) {
        final String workerToken = "worker-" + w;
        executor.submit(
            () -> {
              try {
                startLatch.await();
                Optional<ClaimedControllerWork> work =
                    controllerWorkStore.claimNext(now, workerToken, now.plusSeconds(30));
                work.ifPresent(
                    claimedControllerWork -> {
                      // 确保无重复抢占（同一 Issue 只能被一个 Worker 独占抢占）
                      boolean added = claimedIssues.add(claimedControllerWork.getIssueId());
                      assertTrue(added, "Duplicate claim detected across workers!");
                    });
              } catch (Exception ignored) {
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      assertTrue(
          doneLatch.await(10, TimeUnit.SECONDS), "Concurrent claims must finish within timeout");
    } finally {
      executor.shutdownNow();
    }

    // 5 个由于 FOR UPDATE SKIP LOCKED，应该各自抢到一个不同的任务，总数恰好为 5
    assertEquals(issueCount, claimedIssues.size());
  }

  @Test
  void testStoreValidationsAndErrorPaths() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Val Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(project.getId(), "Task", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    Instant now = Instant.now();

    // claimNext invalid leaseUntil
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.claimNext(now, "token", now.minusSeconds(1)));

    // renewLease work not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> controllerWorkStore.renewLease(UUID.randomUUID(), "tok", now, now.plusSeconds(30)));

    // completeWork work not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> controllerWorkStore.completeWork(UUID.randomUUID(), "tok", 1L, now));

    // rescheduleWork work not found
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            controllerWorkStore.rescheduleWork(
                UUID.randomUUID(), "tok", 1L, now, now.plusSeconds(30)));

    controllerWorkStore.requestWork(issueId, now.minusSeconds(5));
    Optional<ClaimedControllerWork> claimed =
        controllerWorkStore.claimNext(now, "tok", now.plusSeconds(30));
    assertTrue(claimed.isPresent());

    // renewLease newLeaseUntil not after current
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.renewLease(issueId, "tok", now, now.plusSeconds(10)));

    // renewLease lease expired
    assertThrows(
        AiValidationException.class,
        () ->
            controllerWorkStore.renewLease(
                issueId, "tok", now.plusSeconds(40), now.plusSeconds(50)));

    // completeWork token mismatch
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.completeWork(issueId, "wrong", 1L, now));

    // completeWork lease expired
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.completeWork(issueId, "tok", 1L, now.plusSeconds(40)));

    // completeWork claimedWakeVersion > current wakeVersion
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.completeWork(issueId, "tok", 999L, now));

    // rescheduleWork token mismatch
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.rescheduleWork(issueId, "wrong", 1L, now, now.plusSeconds(60)));

    // rescheduleWork lease expired
    assertThrows(
        AiValidationException.class,
        () ->
            controllerWorkStore.rescheduleWork(
                issueId, "tok", 1L, now.plusSeconds(40), now.plusSeconds(60)));

    // rescheduleWork claimedWakeVersion > current
    assertThrows(
        AiValidationException.class,
        () -> controllerWorkStore.rescheduleWork(issueId, "tok", 999L, now, now.plusSeconds(60)));
  }
}
