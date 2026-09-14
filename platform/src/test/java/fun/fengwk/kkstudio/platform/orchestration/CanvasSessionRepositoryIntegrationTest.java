package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;

import java.util.List;
import java.util.UUID;

/** {@code session_owner.canvas_id} 归属仓库在真实 PostgreSQL 上的契约：插入/查找/枚举/删除、FK 防悬空与 session 单归属互斥。 */
class CanvasSessionRepositoryIntegrationTest extends OwnerTestSupport {

  @Autowired private CanvasSessionRepository canvasSessionRepository;

  /** 插入后可按 session/canvas 两个方向读取，owner 枚举按归属时间倒序，删除返回受影响行数与幂等。 */
  @Test
  void insertFindEnumerateAndDelete() {
    UUID canvasId = canvasOwner();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    sessionRow(first);
    sessionRow(second);

    assertTrue(canvasSessionRepository.insert(first, canvasId));
    assertTrue(canvasSessionRepository.insert(second, canvasId));

    assertEquals(
        new CanvasSession(first, canvasId), canvasSessionRepository.findBySessionId(first));
    assertEquals(
        new CanvasSession(second, canvasId), canvasSessionRepository.findBySessionId(second));
    assertNull(canvasSessionRepository.findBySessionId(UUID.randomUUID()));

    List<UUID> listed = canvasSessionRepository.listSessionIds(canvasId);
    assertEquals(2, listed.size());
    assertTrue(listed.contains(first) && listed.contains(second));

    assertEquals(1, canvasSessionRepository.deleteBySessionId(first));
    assertEquals(0, canvasSessionRepository.deleteBySessionId(first), "重复删除必须幂等返回 0 行");
    assertNull(canvasSessionRepository.findBySessionId(first));
    assertEquals(List.of(second), canvasSessionRepository.listSessionIds(canvasId));
  }

  /** session_id 是主键：同一 Session 不能被第二次持有（无论归属本 Canvas 还是另一 Canvas）。 */
  @Test
  void sessionIdIsSingleOwnerPrimaryKey() {
    UUID canvasA = canvasOwner();
    UUID canvasB = canvasOwner();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertTrue(canvasSessionRepository.insert(sessionId, canvasA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> canvasSessionRepository.insert(sessionId, canvasA));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> canvasSessionRepository.insert(sessionId, canvasB));
    assertEquals(
        1L,
        count(
            "select count(*) from session_owner where session_id = ? and canvas_id is not null",
            sessionId));
  }

  /** FK：canvas 与 harness_session 都必须存在，防止出现悬空归属。 */
  @Test
  void foreignKeysRejectOrphanedRows() {
    UUID canvasId = canvasOwner();
    UUID sessionId = UUID.randomUUID();
    sessionRow(sessionId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> canvasSessionRepository.insert(sessionId, UUID.randomUUID()),
        "missing canvas owner must fail the canvas_id FK");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> canvasSessionRepository.insert(UUID.randomUUID(), canvasId),
        "missing harness_session must fail the session_id FK");
  }
}
