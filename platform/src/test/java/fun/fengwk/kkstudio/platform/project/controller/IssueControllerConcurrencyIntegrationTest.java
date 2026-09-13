package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.ProjectTestSupport;
import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Issue Controller 的 PostgreSQL 竞争与运行时集成测试。
 *
 * <p>覆盖过期 claim fencing、同 Issue 单 Run/Session 原子性，以及 bounded dispatcher 对多 Issue 的真实端到端 handoff。
 */
class IssueControllerConcurrencyIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueRunSessionRepository issueRunSessionRepository;
  @Autowired private IssueControllerWorkStore workStore;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueReconciler reconciler;

  @Test
  void concurrentExpiredAndCurrentClaimsCreateExactlyOneRun() throws Exception {
    // 测试意图：旧 worker 即使先拿到业务行锁，也必须在 fencing 失败后完整回滚，不得创建重复 Run。
    String agent = createTestAgent();
    Project project = projectService.createProject("Fence Project", "Description", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Fence Issue", "Description", agent, null, IssueStatus.TODO);
    Instant firstClaimAt = Instant.now().plusSeconds(1);
    ClaimedControllerWork expired =
        workStore
            .claimNext(firstClaimAt, "expired-worker", firstClaimAt.plus(Duration.ofMillis(100)))
            .orElseThrow();
    Instant reclaimAt = firstClaimAt.plusSeconds(1);
    ClaimedControllerWork current =
        workStore.claimNext(reclaimAt, "current-worker", reclaimAt.plusSeconds(30)).orElseThrow();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> expiredResult =
          executor.submit(() -> reconcileCapturing(reconciler, expired, start));
      Future<Object> currentResult =
          executor.submit(() -> reconcileCapturing(reconciler, current, start));
      start.countDown();
      List<Object> results =
          List.of(expiredResult.get(10, TimeUnit.SECONDS), currentResult.get(10, TimeUnit.SECONDS));

      assertEquals(
          1, results.stream().filter(IssueReconcileOutcome.EXECUTOR_STARTED::equals).count());
      Object rejected =
          results.stream()
              .filter(AiValidationException.class::isInstance)
              .findFirst()
              .orElseThrow();
      assertInstanceOf(AiValidationException.class, rejected);

      IssueRun active = issueRunRepository.findActiveByIssueId(issue.getId());
      assertNotNull(active);
      assertEquals(
          1,
          jdbcTemplate.queryForObject(
              "select count(*) from issue_run where issue_id = ?", Integer.class, issue.getId()));
      assertNull(issueRunSessionRepository.findByRunId(active.getId()));
      assertEquals(IssueStatus.IN_PROGRESS, issueRepository.getById(issue.getId()).getStatus());
      assertNull(workStore.getWork(issue.getId()).getLeaseToken());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void dispatcherHandsOffTwoDueIssuesThroughRealTransactions() throws Exception {
    // 测试意图：真实 claim -> bounded handoff -> reconcile 链路可并发创建两个独立 Run，且容量最终归零。
    String agent = createTestAgent();
    Project project = projectService.createProject("Dispatch Project", "Description", agent);
    Issue first =
        issueService.createIssue(
            project.getId(), "First Issue", "Description", agent, null, IssueStatus.TODO);
    Issue second =
        issueService.createIssue(
            project.getId(), "Second Issue", "Description", agent, null, IssueStatus.TODO);

    IssueControllerProperties dispatcherProperties = new IssueControllerProperties();
    dispatcherProperties.setPollInterval(Duration.ofHours(1));
    dispatcherProperties.setMaxDispatchTasks(2);
    ExecutorService drainExecutor = Executors.newSingleThreadExecutor();
    ExecutorService workerExecutor = Executors.newFixedThreadPool(2);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    IssueControllerDispatcher dispatcher =
        new IssueControllerDispatcher(
            workStore,
            reconciler,
            dispatcherProperties,
            Clock.systemUTC(),
            drainExecutor,
            workerExecutor,
            scheduler);
    try {
      dispatcher.start();
      await(
          () ->
              issueRunRepository.findActiveByIssueId(first.getId()) != null
                  && issueRunRepository.findActiveByIssueId(second.getId()) != null
                  && dispatcher.dispatchCapacity() == 0,
          Duration.ofSeconds(10));

      assertEquals(IssueStatus.IN_PROGRESS, issueRepository.getById(first.getId()).getStatus());
      assertEquals(IssueStatus.IN_PROGRESS, issueRepository.getById(second.getId()).getStatus());
      assertEquals(
          2,
          jdbcTemplate.queryForObject(
              "select count(*) from issue_run where issue_id in (?, ?)",
              Integer.class,
              first.getId(),
              second.getId()));
      assertNotNull(workStore.getWork(first.getId()));
      assertNotNull(workStore.getWork(second.getId()));
    } finally {
      dispatcher.close();
      shutdown(drainExecutor);
      shutdown(workerExecutor);
      shutdown(scheduler);
    }
  }

  private Object reconcileCapturing(
      IssueReconciler reconciler, ClaimedControllerWork claim, CountDownLatch start) {
    try {
      start.await();
      return reconciler.reconcile(claim);
    } catch (RuntimeException failure) {
      return failure;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return interrupted;
    }
  }

  private void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
  }

  private void shutdown(ExecutorService executor) throws InterruptedException {
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }
}
