package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 用独立 PostgreSQL 事务验证 Chat 归属锁与深删除锁的兼容性。 */
class ChatOwnershipLockPostgresIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ChatRepository chats;

  /** KEY SHARE 允许非键更新，但删除锁必须等待接受事务结束。 */
  @Test
  void keyShareBlocksDeletionButNotNonKeyUpdate() throws Exception {
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
