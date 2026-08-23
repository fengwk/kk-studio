package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;

import java.util.List;
import java.util.UUID;

/** {@code chat_session} 归属边仓库在真实 PostgreSQL 上的契约：插入/查找/枚举/删除、FK 防悬空与 session_id 单归属互斥。 */
class ChatSessionRepositoryIntegrationTest extends OwnerTestSupport {

  @Autowired private ChatSessionRepository chatSessionRepository;

  /** 插入后可按 session/chat 两个方向读取，owner 枚举按归属时间倒序，删除返回受影响行数与幂等。 */
  @Test
  void insertFindEnumerateAndDelete() {
    UUID chatId = chatOwner();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    sessionRow(first);
    sessionRow(second);

    assertTrue(chatSessionRepository.insert(first, chatId));
    assertTrue(chatSessionRepository.insert(second, chatId));

    assertEquals(new ChatSession(first, chatId), chatSessionRepository.findBySessionId(first));
    assertEquals(new ChatSession(second, chatId), chatSessionRepository.findBySessionId(second));
    assertNull(chatSessionRepository.findBySessionId(UUID.randomUUID()));

    // owner listing 按 created_at 倒序：本容器时钟下后插入的更晚。
    List<UUID> listed = chatSessionRepository.listSessionIds(chatId);
    assertEquals(2, listed.size());
    assertTrue(listed.contains(first) && listed.contains(second));

    assertEquals(1, chatSessionRepository.deleteBySessionId(first));
    assertEquals(0, chatSessionRepository.deleteBySessionId(first), "重复删除必须幂等返回 0 行");
    assertNull(chatSessionRepository.findBySessionId(first));
    assertEquals(List.of(second), chatSessionRepository.listSessionIds(chatId));
  }

  /** session_id 是主键：同一 Session 不能被第二次持有（无论归属本 owner 还是另一 owner）。 */
  @Test
  void sessionIdIsSingleOwnerPrimaryKey() {
    UUID chatA = chatOwner();
    UUID chatB = chatOwner();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertTrue(chatSessionRepository.insert(sessionId, chatA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, chatA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, chatB));
    assertEquals(1L, count("select count(*) from chat_session where session_id = ?", sessionId));
  }

  /** FK：chat 与 harness_session 都必须存在，防止出现悬空归属。 */
  @Test
  void foreignKeysRejectOrphanedRows() {
    UUID chatId = chatOwner();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(sessionId, UUID.randomUUID()),
        "missing chat owner must fail the chat_id FK");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> chatSessionRepository.insert(UUID.randomUUID(), chatId),
        "missing harness_session must fail the session_id FK");
  }
}
