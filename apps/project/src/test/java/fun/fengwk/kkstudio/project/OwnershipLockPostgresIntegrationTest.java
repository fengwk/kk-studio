package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.chat.Chat;
import fun.fengwk.kkstudio.platform.chat.ChatRepository;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.project.model.Issue;
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
  @Autowired private ChatRepository chats;

  /** KEY SHARE 允许非键更新，但删除锁必须等待接受事务结束。 */
  @Test
  void chatKeyShareBlocksDeletionButNotNonKeyUpdate() throws Exception {
    UUID chatId = UUID.randomUUID();
    Chat chat = new Chat();
    chat.setId(chatId);
    chat.setTitle("lock test");
    chat.setAgentName("schema-agent");
    assertTrue(chats.create(chat));
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> acceptance =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            chats.lockForKeyShare(chatId);
                            locked.countDown();
                            await(release);
                          }));
      try {
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        assertTrue(canLock("select id from chat where id = ? for no key update nowait", chatId));
        assertFalse(canLock("select id from chat where id = ? for update nowait", chatId));
      } finally {
        release.countDown();
      }
      acceptance.get(10, TimeUnit.SECONDS);
      assertTrue(canLock("select id from chat where id = ? for update nowait", chatId));
    }
  }

  /** 先占住 Issue，再验证接受线程等待 Issue 时已持有 Project SHARE；删除排他锁与之冲突。 */
  @Test
  void projectSharePrecedesIssueUpdateAndExcludesDeletion() throws Exception {
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
