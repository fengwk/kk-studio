package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.service.ProjectSessionOwnership;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 用独立 PostgreSQL 事务验证归属锁的兼容性及获得顺序。 */
class OwnershipLockPostgresIntegrationTest extends ProjectTestSupport {

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ProjectSessionOwnership ownership;

  /** 先占住 Issue，再验证接受线程等待 Issue 时已持有 Project SHARE；深删除的 KEY SHARE 与之兼容，排他写锁被阻塞。 */
  @Test
  void projectSharePrecedesIssueUpdateAndKeepsDeletionCompatible() throws Exception {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    SessionOwner owner = ProjectSessionOwnership.owner(issue.getId(), "agent");
    AtomicInteger backend = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Connection blocker = dataSource.getConnection();
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, "select id from project_issue where id = ? for update", issue.getId());
      Future<?> acceptance =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            backend.set(
                                jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            started.countDown();
                            ownership.lockForAcceptance(owner, null);
                            locked.countDown();
                            await(release);
                          }));
      try {
        assertTrue(started.await(10, TimeUnit.SECONDS));
        waitUntilBlocked(backend.get());
        assertTrue(canLock("select id from project where id = ? for share nowait", projectId));
        assertTrue(canLock("select id from project where id = ? for key share nowait", projectId));
        assertFalse(
            canLock("select id from project where id = ? for no key update nowait", projectId));
        blocker.commit();
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        assertFalse(
            canLock("select id from project_issue where id = ? for update nowait", issue.getId()));
        assertFalse(canLock("select id from project where id = ? for update nowait", projectId));
      } finally {
        blocker.rollback();
        release.countDown();
      }
      acceptance.get(10, TimeUnit.SECONDS);
      assertTrue(canLock("select id from project where id = ? for update nowait", projectId));
    }
  }

  /**
   * 两个绑定了 Session 的 Issue 在同一 Project 中并发深删除都能成功。
   *
   * <p>确定性交错：测试连接先持有两行 Issue 的排他锁，两个删除事务各自取得 Project KEY SHARE 后停在 Issue 行上；只有两个后端都已等待才放开。旧实现随后会为
   * Session 深删除把 Project 升级为排他锁，两个事务互相等待而必然死锁；与 Issue 写路径同锁序后各自直接完成。
   */
  @Test
  void concurrentSessionBoundIssueDeletionsShareProjectKeyShare() throws Exception {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("并发删除", agent, agent, 3);
    Issue first = createIssue(projectId);
    Issue second = createIssue(projectId);
    finishedRun(first);
    finishedRun(second);
    long firstVersion = projectIssueVersion(first.getId());
    long secondVersion = projectIssueVersion(second.getId());

    AtomicInteger firstBackend = new AtomicInteger();
    AtomicInteger secondBackend = new AtomicInteger();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    try (Connection blocker = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      lock(blocker, "select id from project_issue where id = ? for update", first.getId());
      lock(blocker, "select id from project_issue where id = ? for update", second.getId());
      Future<?> firstDeletion =
          executor.submit(
              () -> deleteIssue(first.getId(), firstVersion, firstBackend, firstStarted));
      Future<?> secondDeletion =
          executor.submit(
              () -> deleteIssue(second.getId(), secondVersion, secondBackend, secondStarted));
      try {
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
        waitUntilBlocked(firstBackend.get());
        assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
        waitUntilBlocked(secondBackend.get());
        assertEquals(2L, projectRowShareLocks(firstBackend.get(), secondBackend.get()));
        blocker.rollback();
        firstDeletion.get(10, TimeUnit.SECONDS);
        secondDeletion.get(10, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    assertEquals(0L, count("select count(*) from project_issue where project_id = ?", projectId));
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id in (?, ?)",
            first.getId(),
            second.getId()));
    assertEquals(1L, count("select count(*) from project where id = ?", projectId));
  }

  /** 在一个独立物理事务中执行真实深删除，并记录该事务的 PostgreSQL 后端以便观察等待。 */
  private void deleteIssue(
      UUID issueId, long expectedVersion, AtomicInteger backend, CountDownLatch started) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              backend.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
              started.countDown();
              issueLifecycleService.deleteIssue(issueId, expectedVersion);
            });
  }

  /** 让 Issue 拥有一个已收尾 Run，从而留下真实 Session/Thread 绑定且不阻塞深删除。 */
  private void finishedRun(Issue issue) {
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun run = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(run.getThreadId()),
        null,
        null,
        null);
  }

  private long projectIssueVersion(UUID issueId) {
    Long version =
        jdbc.queryForObject("select version from project_issue where id = ?", Long.class, issueId);
    return version == null ? 0L : version;
  }

  /** 两个删除事务在 Project 行上持有的共享表锁；深删除不再升级为排他锁。 */
  private long projectRowShareLocks(int firstBackend, int secondBackend) {
    Long locks =
        jdbc.queryForObject(
            "select count(*) from pg_locks where relation = 'project'::regclass"
                + " and mode = 'RowShareLock' and granted and pid in (?, ?)",
            Long.class,
            firstBackend,
            secondBackend);
    return locks == null ? 0L : locks;
  }

  private boolean canLock(String sql, UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try {
        lock(connection, sql, id);
        return true;
      } catch (SQLException error) {
        assertEquals("55P03", error.getSQLState());
        return false;
      }
    }
  }

  private static void lock(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
      }
    }
  }

  private void waitUntilBlocked(int pid) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      Boolean blocked =
          jdbc.queryForObject("select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid);
      if (Boolean.TRUE.equals(blocked)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("acceptance did not wait for the issue row");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test transaction");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }
}
