package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 验证 IssueRun 并发操作、终态幂等 Exact Replay 竞态收敛及互斥安全： 1. duplicate completeExecutorRun、agent
 * reviewByAgent、human reviewByHuman 并发 exact replay，两调用方均成功获得同一 Run 实例，且 CHANGES_REQUESTED 仅追加一条
 * REVIEW_DECISION Activity； 2. complete 与 cancel 并发竞态，无死锁、无半迁移，最终状态严格收敛； 3. 跨 Issue 与同 Issue 并发
 * startExecutorRun 单活跃与 ordinal 严格保证。
 */
class IssueRunConcurrencyIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;

  @Test
  void testDuplicateSubmitExactReplayConcurrency() throws Exception {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Submit Concurrency", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(proj.getId(), "Issue", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(issue.getId(), agent, Instant.now().plusSeconds(3600), 0);

    String actionId = "submit-action-" + UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<IssueRun> task1 =
          () -> {
            barrier.await();
            return issueRunService.completeExecutorRun(
                run.getId(), actionId, "Summary", "Verification");
          };

      Callable<IssueRun> task2 =
          () -> {
            barrier.await();
            return issueRunService.completeExecutorRun(
                run.getId(), actionId, "Summary", "Verification");
          };

      Future<IssueRun> f1 = executor.submit(task1);
      Future<IssueRun> f2 = executor.submit(task2);

      IssueRun r1 = f1.get(10, TimeUnit.SECONDS);
      IssueRun r2 = f2.get(10, TimeUnit.SECONDS);

      assertNotNull(r1);
      assertNotNull(r2);
      assertEquals(r1.getId(), r2.getId());
      assertEquals(IssueRunStatus.COMPLETED, r1.getStatus());
      assertEquals(IssueRunOutcome.SUBMITTED, r1.getOutcome());
      assertEquals(actionId, r1.getTerminalActionId());

      Issue refreshed = issueService.getIssue(issue.getId());
      assertEquals(IssueStatus.IN_REVIEW, refreshed.getStatus());

      // 仅有一个 Run 实体
      List<IssueRun> runs = issueRunRepository.listByIssueId(issue.getId());
      assertEquals(1, runs.size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testDuplicateAgentReviewExactReplayConcurrency() throws Exception {
    String executorAgent = createTestAgent();
    String reviewerAgent = createTestAgent();
    Project proj = projectService.createProject("Agent Review Concurrency", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Review Issue", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);

    IssueRun execRun =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);
    issueRunService.completeExecutorRun(
        execRun.getId(), "submit-" + UUID.randomUUID(), "Done work", "Passed tests");

    IssueRun revRun =
        issueRunService.startReviewerRun(
            issue.getId(), reviewerAgent, Instant.now().plusSeconds(3600), 0);

    String actionId = "agent-review-action-" + UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<IssueRun> task1 =
          () -> {
            barrier.await();
            return issueRunService.reviewByAgent(
                revRun.getId(), reviewerAgent, actionId, ReviewDecision.APPROVE, "LGTM");
          };

      Callable<IssueRun> task2 =
          () -> {
            barrier.await();
            return issueRunService.reviewByAgent(
                revRun.getId(), reviewerAgent, actionId, ReviewDecision.APPROVE, "LGTM");
          };

      Future<IssueRun> f1 = executor.submit(task1);
      Future<IssueRun> f2 = executor.submit(task2);

      IssueRun r1 = f1.get(10, TimeUnit.SECONDS);
      IssueRun r2 = f2.get(10, TimeUnit.SECONDS);

      assertEquals(r1.getId(), r2.getId());
      assertEquals(IssueRunStatus.COMPLETED, r1.getStatus());
      assertEquals(IssueRunOutcome.APPROVED, r1.getOutcome());
      assertEquals(actionId, r1.getTerminalActionId());

      Issue refreshed = issueService.getIssue(issue.getId());
      assertEquals(IssueStatus.DONE, refreshed.getStatus());

      // 仅记录 1 条 REVIEW_DECISION Activity（包含初始 SPEC_CHANGE 共 2 条）
      List<IssueActivity> activities = issueActivityRepository.listByIssueId(issue.getId());
      assertEquals(2, activities.size());
      assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(0).getKind());
      assertEquals(IssueActivityKind.REVIEW_DECISION, activities.get(1).getKind());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testDuplicateHumanReviewExactReplayConcurrency() throws Exception {
    String executorAgent = createTestAgent();
    Project proj = projectService.createProject("Human Review Concurrency", "Desc", true, 3);
    // reviewerAgentName 为空，允许人工评审
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Human Issue", "Desc", executorAgent, null, IssueStatus.TODO);

    IssueRun execRun =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);
    issueRunService.completeExecutorRun(
        execRun.getId(), "submit-" + UUID.randomUUID(), "Executor summary", "Executor verify");

    String idempotencyKey = "human-review-key-" + UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<Void> task1 =
          () -> {
            barrier.await();
            issueRunService.reviewByHuman(
                issue.getId(), ReviewDecision.REQUEST_CHANGES, "Need fix", idempotencyKey);
            return null;
          };

      Callable<Void> task2 =
          () -> {
            barrier.await();
            issueRunService.reviewByHuman(
                issue.getId(), ReviewDecision.REQUEST_CHANGES, "Need fix", idempotencyKey);
            return null;
          };

      Future<Void> f1 = executor.submit(task1);
      Future<Void> f2 = executor.submit(task2);

      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);

      Issue refreshed = issueService.getIssue(issue.getId());
      assertEquals(IssueStatus.TODO, refreshed.getStatus());

      // 幂等断言：无论并发线程谁先提交，数据库中只能产生 1 条 REVIEW_DECISION Activity（共 2 条含初始 SPEC_CHANGE）
      List<IssueActivity> activities = issueActivityRepository.listByIssueId(issue.getId());
      assertEquals(2, activities.size());
      assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(0).getKind());
      IssueActivity reviewActivity = activities.get(1);
      assertEquals(IssueActivityKind.REVIEW_DECISION, reviewActivity.getKind());
      assertEquals(IssueActivityActorType.HUMAN, reviewActivity.getActorType());
      assertEquals("Need fix", reviewActivity.getBody());
      assertEquals(idempotencyKey, reviewActivity.getIdempotencyKey());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testSubmitVsCancelConcurrency() throws Exception {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Submit vs Cancel", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(proj.getId(), "Issue", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(issue.getId(), agent, Instant.now().plusSeconds(3600), 0);

    String submitActionId = "submit-action-" + UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<String> submitTask =
          () -> {
            barrier.await();
            try {
              issueRunService.completeExecutorRun(run.getId(), submitActionId, "Summ", "Ver");
              return "SUBMIT_OK";
            } catch (AiValidationException e) {
              return "SUBMIT_FAILED";
            }
          };

      Callable<String> cancelTask =
          () -> {
            barrier.await();
            try {
              Issue cur = issueService.getIssue(issue.getId());
              issueService.cancelIssue(issue.getId(), cur.getVersion(), "Cancelling");
              return "CANCEL_OK";
            } catch (AiVersionConflictException e) {
              return "CANCEL_CONFLICT";
            }
          };

      Future<String> fSubmit = executor.submit(submitTask);
      Future<String> fCancel = executor.submit(cancelTask);

      String rSubmit = fSubmit.get(10, TimeUnit.SECONDS);
      String rCancel = fCancel.get(10, TimeUnit.SECONDS);

      Issue finalIssue = issueService.getIssue(issue.getId());
      IssueRun finalRun = issueRunService.getRun(run.getId());

      if ("SUBMIT_OK".equals(rSubmit)) {
        // Complete 成功执行：Run 变为 COMPLETED / SUBMITTED
        assertEquals(IssueRunStatus.COMPLETED, finalRun.getStatus());
        assertEquals(IssueRunOutcome.SUBMITTED, finalRun.getOutcome());
        if ("CANCEL_OK".equals(rCancel)) {
          // Cancel 后续成功取消了 Issue，但已完成的 Run 保持 COMPLETED
          assertEquals(IssueStatus.CANCELED, finalIssue.getStatus());
        } else {
          assertEquals("CANCEL_CONFLICT", rCancel);
          assertEquals(IssueStatus.IN_REVIEW, finalIssue.getStatus());
        }
      } else {
        // Cancel 先占锁并将 Issue 与 Run 设为 CANCELED/CANCELLED
        assertEquals("CANCEL_OK", rCancel);
        assertEquals(IssueStatus.CANCELED, finalIssue.getStatus());
        assertEquals(IssueRunStatus.CANCELLED, finalRun.getStatus());
        assertNotNull(finalRun.getWaitingReason());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testStartExecutorRunConcurrency() throws Exception {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Start Run Concurrency", "Desc", true, 3);

    // 1. 两个不同 Issue 并发启动：均应成功且各自序号 ordinal = 1
    Issue issueA =
        issueService.createIssue(proj.getId(), "A", "Desc", agent, null, IssueStatus.TODO);
    Issue issueB =
        issueService.createIssue(proj.getId(), "B", "Desc", agent, null, IssueStatus.TODO);

    CyclicBarrier barrier1 = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<IssueRun> taskA =
          () -> {
            barrier1.await();
            return issueRunService.startExecutorRun(
                issueA.getId(), agent, Instant.now().plusSeconds(3600), 0);
          };
      Callable<IssueRun> taskB =
          () -> {
            barrier1.await();
            return issueRunService.startExecutorRun(
                issueB.getId(), agent, Instant.now().plusSeconds(3600), 0);
          };

      Future<IssueRun> fa = executor.submit(taskA);
      Future<IssueRun> fb = executor.submit(taskB);

      IssueRun ra = fa.get(10, TimeUnit.SECONDS);
      IssueRun rb = fb.get(10, TimeUnit.SECONDS);

      assertEquals(1L, ra.getOrdinal());
      assertEquals(1L, rb.getOrdinal());
      assertEquals(0, ra.getMaxContinuations());
      assertEquals(0, rb.getMaxContinuations());

      // 2. 同一个 Issue 并发启动：恰好一个成功，另一个稳定失败抛出 AiValidationException
      Issue issueC =
          issueService.createIssue(proj.getId(), "C", "Desc", agent, null, IssueStatus.TODO);
      CyclicBarrier barrier2 = new CyclicBarrier(2);

      Callable<String> taskC1 =
          () -> {
            barrier2.await();
            try {
              issueRunService.startExecutorRun(
                  issueC.getId(), agent, Instant.now().plusSeconds(3600), 0);
              return "C1_OK";
            } catch (AiValidationException e) {
              return "C1_FAILED";
            }
          };

      Callable<String> taskC2 =
          () -> {
            barrier2.await();
            try {
              issueRunService.startExecutorRun(
                  issueC.getId(), agent, Instant.now().plusSeconds(3600), 0);
              return "C2_OK";
            } catch (AiValidationException e) {
              return "C2_FAILED";
            }
          };

      Future<String> fc1 = executor.submit(taskC1);
      Future<String> fc2 = executor.submit(taskC2);

      String rc1 = fc1.get(10, TimeUnit.SECONDS);
      String rc2 = fc2.get(10, TimeUnit.SECONDS);

      boolean c1Won = "C1_OK".equals(rc1) && "C2_FAILED".equals(rc2);
      boolean c2Won = "C2_OK".equals(rc2) && "C1_FAILED".equals(rc1);
      assertTrue(c1Won || c2Won, "Exactly one run creation must succeed for the same issue");

      List<IssueRun> runsC = issueRunRepository.listByIssueId(issueC.getId());
      assertEquals(1, runsC.size());
      assertEquals(1L, runsC.getFirst().getOrdinal());
    } finally {
      executor.shutdownNow();
    }
  }
}
