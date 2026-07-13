package fun.fengwk.kkstudio.core.harness.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.InvalidSessionTreeException;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionLeafConflictException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = CoreTestApplication.class)
class MysqlHarnessSessionStoreTest {
  @Autowired private MysqlHarnessSessionStore store;

  /** H2 adapter 的 CAS 冲突回滚不会留下可被读取的孤儿 Entry。 */
  @Test
  void shouldAppendWithLeafCasAndRollbackConflict() {
    String sessionId = id("session");
    store.create(new Session(sessionId, 1L, null, null, Instant.now()));
    SessionEntry root = entry(id("entry"), sessionId, null);
    store.append(root, null);

    SessionEntry current = entry(id("entry"), sessionId, root.entryId());
    store.append(current, root.entryId());
    SessionEntry stale = entry(id("entry"), sessionId, root.entryId());
    assertThrows(SessionLeafConflictException.class, () -> store.append(stale, root.entryId()));
    assertEquals(current.entryId(), store.find(sessionId).orElseThrow().leafEntryId());
    assertFalse(store.find(sessionId, stale.entryId()).isPresent());
  }

  /** Store 按 session_id 回溯路径，跨 Session Entry 和跨 Workspace fork 均被拒绝。 */
  @Test
  void shouldRejectCrossSessionPathAndCrossWorkspaceFork() {
    String sourceId = id("source");
    String otherId = id("other");
    store.create(new Session(sourceId, 1L, null, null, Instant.now()));
    store.create(new Session(otherId, 2L, null, null, Instant.now()));
    SessionEntry root = entry(id("entry"), sourceId, null);
    store.append(root, null);

    assertThrows(InvalidSessionTreeException.class, () -> store.loadPath(otherId, root.entryId()));
    Session crossWorkspaceFork =
        new Session(id("fork"), 2L, sourceId, null, Instant.now());
    assertThrows(
        InvalidSessionTreeException.class, () -> store.createFork(crossWorkspaceFork, List.of()));
  }

  private static SessionEntry entry(String entryId, String sessionId, String parentEntryId) {
    AgentSnapshot snapshot =
        new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}");
    return new SessionEntry(
        entryId,
        sessionId,
        parentEntryId,
        new AgentSnapshotEntryPayload(snapshot).type(),
        new AgentSnapshotEntryPayload(snapshot),
        Instant.now());
  }

  private static String id(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
  }
}
