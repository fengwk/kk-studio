package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证 IssueDependency 依赖边管理及无环 DAG 保证： 包含同项目复合外键校验、自环禁止、递归 CTE 环检测以及并发反向边尝试下的绝对无环保证。 */
class IssueDependencyIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;
  @Autowired private IssueControllerWorkStore controllerWorkStore;

  @Test
  void testSameProjectAndSelfDependencyEnforcement() {
    String agent = createTestAgent();
    Project proj1 = projectService.createProject("Project 1", "Desc", agent);
    Project proj2 = projectService.createProject("Project 2", "Desc", agent);

    Issue issueA =
        issueService.createIssue(proj1.getId(), "Issue A", "Desc", agent, null, IssueStatus.TODO);
    Issue issueB =
        issueService.createIssue(proj1.getId(), "Issue B", "Desc", agent, null, IssueStatus.TODO);
    Issue issueOther =
        issueService.createIssue(
            proj2.getId(), "Issue Other", "Desc", agent, null, IssueStatus.TODO);

    // 自依赖拒绝
    assertThrows(
        AiValidationException.class,
        () -> issueService.addDependency(issueA.getId(), issueA.getId(), 0L));

    // 跨项目依赖拒绝
    assertThrows(
        AiValidationException.class,
        () -> issueService.addDependency(issueA.getId(), issueOther.getId(), 0L));

    // 同项目依赖成功
    issueService.addDependency(issueA.getId(), issueB.getId(), 0L);
    List<IssueDependency> deps = issueService.listDependencies(issueA.getId());
    assertEquals(1, deps.size());
    assertEquals(issueB.getId(), deps.getFirst().getDependsOnIssueId());

    // 重复依赖拒绝
    assertThrows(
        AiValidationException.class,
        () -> issueService.addDependency(issueA.getId(), issueB.getId(), 1L));
  }

  @Test
  void testSpecRevisionAndWorkRequestOnDependencyModification() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Dep Lifecycle", "Desc", agent);
    Issue issueA =
        issueService.createIssue(proj.getId(), "A", "Desc", agent, null, IssueStatus.TODO);
    Issue issueB =
        issueService.createIssue(proj.getId(), "B", "Desc", agent, null, IssueStatus.TODO);

    long initialSpecRev = issueA.getSpecRevision();

    // 添加依赖递增被阻塞 Issue 的 specRevision 并请求 Work
    issueService.addDependency(issueA.getId(), issueB.getId(), 0L);
    Issue refreshedA = issueService.getIssue(issueA.getId());
    assertEquals(initialSpecRev + 1, refreshedA.getSpecRevision());
    assertNotNull(controllerWorkStore.getWork(issueA.getId()));

    // 移除依赖再次递增 specRevision
    issueService.removeDependency(issueA.getId(), issueB.getId(), refreshedA.getVersion());
    Issue refreshedA2 = issueService.getIssue(issueA.getId());
    assertEquals(initialSpecRev + 2, refreshedA2.getSpecRevision());

    // 只能对 BACKLOG 或 TODO 状态的 Issue 添加/移除依赖
    refreshedA2.setStatus(IssueStatus.IN_PROGRESS);
    issueRepository.updateById(refreshedA2, refreshedA2.getVersion());
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.addDependency(
                issueA.getId(), issueB.getId(), refreshedA2.getVersion() + 1));
  }

  @Test
  void testCycleDetectionDirectTransitiveAndDiamondDag() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("DAG Test", "Desc", agent);
    Issue a = issueService.createIssue(proj.getId(), "A", "Desc", agent, null, IssueStatus.TODO);
    Issue b = issueService.createIssue(proj.getId(), "B", "Desc", agent, null, IssueStatus.TODO);
    Issue c = issueService.createIssue(proj.getId(), "C", "Desc", agent, null, IssueStatus.TODO);
    Issue d = issueService.createIssue(proj.getId(), "D", "Desc", agent, null, IssueStatus.TODO);

    // 1. 直接反向边成环：A -> B, 尝试 B -> A
    issueService.addDependency(a.getId(), b.getId(), a.getVersion());
    assertThrows(
        AiValidationException.class,
        () -> issueService.addDependency(b.getId(), a.getId(), b.getVersion()),
        "Direct cycle B -> A must be detected and rejected");

    // 2. 传递反向边成环：A -> B, B -> C, 尝试 C -> A
    issueService.addDependency(b.getId(), c.getId(), b.getVersion());
    assertThrows(
        AiValidationException.class,
        () -> issueService.addDependency(c.getId(), a.getId(), c.getVersion()),
        "Transitive cycle C -> A must be detected and rejected");

    // 3. 菱形 DAG（Diamond）：
    // A -> B -> D
    // A -> C -> D
    // 不构成有向环，必须成功允许
    issueService.addDependency(a.getId(), c.getId(), issueService.getIssue(a.getId()).getVersion());
    issueService.addDependency(c.getId(), d.getId(), c.getVersion());
    issueService.addDependency(b.getId(), d.getId(), issueService.getIssue(b.getId()).getVersion());

    assertTrue(issueDependencyRepository.checkHasPath(a.getId(), d.getId()));
    assertFalse(issueDependencyRepository.checkHasPath(d.getId(), a.getId()));
  }

  @Test
  void testConcurrentReverseEdgeAttemptsMaintainAcyclicDag() throws InterruptedException {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Concurrency DAG", "Desc", agent);

    // 验证多组并发反向边竞争：每组中线程 1 尝试 X -> Y，线程 2 尝试 Y -> X
    int pairs = 5;
    for (int p = 0; p < pairs; p++) {
      Issue nodeX =
          issueService.createIssue(proj.getId(), "X-" + p, "Desc", agent, null, IssueStatus.TODO);
      Issue nodeY =
          issueService.createIssue(proj.getId(), "Y-" + p, "Desc", agent, null, IssueStatus.TODO);

      UUID idX = nodeX.getId();
      UUID idY = nodeY.getId();

      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(2);

      AtomicInteger successCount = new AtomicInteger(0);
      AtomicBoolean xDependsOnY = new AtomicBoolean(false);
      AtomicBoolean yDependsOnX = new AtomicBoolean(false);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        // 线程 1: X depends on Y
        executor.submit(
            () -> {
              try {
                startLatch.await();
                issueService.addDependency(idX, idY, 0L);
                successCount.incrementAndGet();
                xDependsOnY.set(true);
              } catch (Exception ignored) {
                // 环检测、版本冲突或锁等待失败
              } finally {
                doneLatch.countDown();
              }
            });

        // 线程 2: Y depends on X
        executor.submit(
            () -> {
              try {
                startLatch.await();
                issueService.addDependency(idY, idX, 0L);
                successCount.incrementAndGet();
                yDependsOnX.set(true);
              } catch (Exception ignored) {
                // 环检测、版本冲突或锁等待失败
              } finally {
                doneLatch.countDown();
              }
            });

        startLatch.countDown();
        assertTrue(
            doneLatch.await(10, TimeUnit.SECONDS), "Concurrent reverse attempts must finish");
      } finally {
        executor.shutdownNow();
      }

      // 铁律：绝不能两者都成功（形成双向死环）！成功数至多为 1
      assertFalse(
          xDependsOnY.get() && yDependsOnX.get(),
          "Both reverse edges succeeded simultaneously, violating acyclic DAG invariant!");
      assertTrue(
          successCount.get() <= 1,
          "At most one directional edge can succeed between any two nodes");

      // 验证 DB 中该节点对的边数 <= 1
      List<IssueDependency> depsX = issueDependencyRepository.listByIssueId(idX);
      List<IssueDependency> depsY = issueDependencyRepository.listByIssueId(idY);
      boolean hasXY = depsX.stream().anyMatch(d -> d.getDependsOnIssueId().equals(idY));
      boolean hasYX = depsY.stream().anyMatch(d -> d.getDependsOnIssueId().equals(idX));
      assertFalse(hasXY && hasYX, "Database contains circular dependency edges between X and Y!");
    }
  }

  @Test
  void testDependencyValidationsAndRepositoryQueries() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Dep Val Proj", "Desc", agent);
    Issue a = issueService.createIssue(proj.getId(), "A", "Desc", agent, null, IssueStatus.TODO);
    Issue b = issueService.createIssue(proj.getId(), "B", "Desc", agent, null, IssueStatus.TODO);

    // addDependency issue not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.addDependency(UUID.randomUUID(), b.getId(), 0L));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.addDependency(a.getId(), UUID.randomUUID(), 0L));

    // addDependency version conflict
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.addDependency(a.getId(), b.getId(), 999L));

    issueService.addDependency(a.getId(), b.getId(), 0L);

    // removeDependency version conflict
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.removeDependency(a.getId(), b.getId(), 999L));

    // remove non-existent dependency is a no-op
    Issue c = issueService.createIssue(proj.getId(), "C", "Desc", agent, null, IssueStatus.TODO);
    issueService.removeDependency(a.getId(), c.getId(), 1L);

    // repository queries
    List<IssueDependency> byDep = issueDependencyRepository.listByDependsOnIssueId(b.getId());
    assertEquals(1, byDep.size());
    assertEquals(a.getId(), byDep.get(0).getIssueId());

    List<IssueDependency> byProj = issueDependencyRepository.listByProjectId(proj.getId());
    assertEquals(1, byProj.size());
  }

  @Test
  void testFourNodeDisjointConcurrentCycleDetection() throws Exception {
    String agent = createTestAgent();
    Project proj = projectService.createProject("4-Node Cycle Test", "Desc", agent);

    // 四节点 DAG：预建 B -> C 以及 D -> A
    Issue a = issueService.createIssue(proj.getId(), "A", "Desc", agent, null, IssueStatus.TODO);
    Issue b = issueService.createIssue(proj.getId(), "B", "Desc", agent, null, IssueStatus.TODO);
    Issue c = issueService.createIssue(proj.getId(), "C", "Desc", agent, null, IssueStatus.TODO);
    Issue d = issueService.createIssue(proj.getId(), "D", "Desc", agent, null, IssueStatus.TODO);

    // 预建: B depends on C (B -> C)
    issueService.addDependency(b.getId(), c.getId(), 0L);
    // 预建: D depends on A (D -> A)
    issueService.addDependency(d.getId(), a.getId(), 0L);

    // 并发新增两条端点完全不重叠的边：
    // Task 1: A depends on B (A -> B)
    // Task 2: C depends on D (C -> D)
    // 若两者同时成功，将形成 D -> A -> B -> C -> D 的 4 节点死环！
    // 得益于 Project 级排他锁互斥，两任务必被串行化，恰一个成功、另一个被环检测拦截抛出 AiValidationException。
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<String> task1 =
          () -> {
            barrier.await();
            try {
              issueService.addDependency(a.getId(), b.getId(), 0L);
              return "A_B_SUCCESS";
            } catch (AiValidationException e) {
              return "A_B_CYCLE";
            }
          };

      Callable<String> task2 =
          () -> {
            barrier.await();
            try {
              issueService.addDependency(c.getId(), d.getId(), 0L);
              return "C_D_SUCCESS";
            } catch (AiValidationException e) {
              return "C_D_CYCLE";
            }
          };

      Future<String> f1 = executor.submit(task1);
      Future<String> f2 = executor.submit(task2);

      String r1 = f1.get(10, TimeUnit.SECONDS);
      String r2 = f2.get(10, TimeUnit.SECONDS);

      boolean abWon = "A_B_SUCCESS".equals(r1) && "C_D_CYCLE".equals(r2);
      boolean cdWon = "C_D_SUCCESS".equals(r2) && "A_B_CYCLE".equals(r1);
      assertTrue(
          abWon || cdWon,
          "Exactly one edge must succeed and the other must be rejected by cycle detection");

      // 最终遍历/CTE 断言无任意自达环，不能只数边！
      UUID[] allNodes = {a.getId(), b.getId(), c.getId(), d.getId()};
      for (UUID node : allNodes) {
        assertFalse(
            issueDependencyRepository.checkHasPath(node, node),
            "Node " + node + " must not have a path to itself (no cycle in DAG)");
      }

      // 有向可达性单向断言
      if (abWon) {
        // D -> A -> B -> C
        assertTrue(issueDependencyRepository.checkHasPath(d.getId(), c.getId()));
        assertFalse(issueDependencyRepository.checkHasPath(c.getId(), d.getId()));
      } else {
        // B -> C -> D -> A
        assertTrue(issueDependencyRepository.checkHasPath(b.getId(), a.getId()));
        assertFalse(issueDependencyRepository.checkHasPath(a.getId(), b.getId()));
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
