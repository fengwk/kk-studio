package fun.fengwk.kkstudio.core.harness.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.api.Trigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.InvalidSessionTreeException;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionLeafConflictException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(classes = CoreTestApplication.class)
class MysqlHarnessSessionStoreBranchTest {
  private static final AtomicLong IDS = new AtomicLong(50_000_000L);
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123Z");
  private static final String SNAPSHOT_JSON =
      "{\"snapshot\":{\"systemPrompt\":\"system\",\"modelId\":\"model\","
          + "\"variant\":\"default\",\"tools\":[],\"skills\":[],\"allowedSubagents\":[],"
          + "\"executionPolicyJson\":\"{}\"}}";

  @Autowired private MysqlHarnessSessionStore store;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Root 创建必须完整往返字段，并拒绝 child 或预置 leaf 冒充新 root。 */
  @Test
  void shouldCreateAndFindOnlyFreshRootSessions() {
    long rootId = id();
    Session root = Session.root(rootId, 9L, "root", true, NOW);

    store.create(root);

    assertEquals(root, store.find(rootId).orElseThrow());
    assertTrue(store.find(id()).isEmpty());
    Session child = child(id(), rootId, rootId, null, 1);
    assertThrows(IllegalArgumentException.class, () -> store.create(child));
    long invalidRootId = id();
    Session rootWithLeaf =
        new Session(
            invalidRootId,
            9L,
            "invalid",
            id(),
            null,
            null,
            invalidRootId,
            null,
            0,
            false,
            0,
            NOW,
            NOW);
    assertThrows(IllegalArgumentException.class, () -> store.create(rootWithLeaf));
  }

  /** Fork 必须持久化完整根到叶链，并拒绝未知 parent、错误链和 leaf 不一致。 */
  @Test
  void shouldCreateOnlyConsistentForkChains() {
    long rootId = id();
    store.create(Session.root(rootId, 9L, "root", true, NOW));
    long childId = id();
    SessionEntry first = entry(id(), childId, null, 101L);
    SessionEntry second = entry(id(), childId, first.id(), null);
    Session child = child(childId, rootId, rootId, second.id(), 1);

    store.createFork(child, List.of(first, second));

    assertEquals(child, store.find(childId).orElseThrow());
    assertEquals(List.of(first, second), store.loadPath(childId, second.id()));
    assertEquals(first, store.find(childId, first.id()).orElseThrow());

    long emptyChildId = id();
    Session emptyChild = child(emptyChildId, rootId, rootId, null, 1);
    store.createFork(emptyChild, List.of());
    assertEquals(emptyChild, store.find(emptyChildId).orElseThrow());

    Session missingParent = child(id(), id(), rootId, null, 1);
    assertThrows(IllegalArgumentException.class, () -> store.createFork(missingParent, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.createFork(Session.root(id(), null, null, false, NOW), List.of()));

    long wrongSessionChildId = id();
    SessionEntry wrongSessionEntry = entry(id(), id(), null, null);
    Session wrongSessionChild =
        child(wrongSessionChildId, rootId, rootId, wrongSessionEntry.id(), 1);
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.createFork(wrongSessionChild, List.of(wrongSessionEntry)));

    long brokenChainChildId = id();
    SessionEntry brokenFirst = entry(id(), brokenChainChildId, null, null);
    SessionEntry brokenSecond = entry(id(), brokenChainChildId, id(), null);
    Session brokenChain = child(brokenChainChildId, rootId, rootId, brokenSecond.id(), 1);
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.createFork(brokenChain, List.of(brokenFirst, brokenSecond)));

    long wrongLeafChildId = id();
    SessionEntry onlyEntry = entry(id(), wrongLeafChildId, null, null);
    Session wrongLeaf = child(wrongLeafChildId, rootId, rootId, id(), 1);
    assertThrows(
        InvalidSessionTreeException.class, () -> store.createFork(wrongLeaf, List.of(onlyEntry)));
  }

  /** Append 必须校验 Session/version/parent，并确保跨 Session parent 不可引用。 */
  @Test
  void shouldRejectInvalidAppendPreconditions() {
    SessionEntry unknownSessionEntry = entry(id(), id(), null, null);
    assertThrows(IllegalArgumentException.class, () -> store.append(unknownSessionEntry, null, 0L));

    long sessionId = id();
    store.create(Session.root(sessionId, null, null, false, NOW));
    SessionEntry root = entry(id(), sessionId, null, null);
    store.append(root, null, 0L);
    assertEquals(root, store.find(sessionId, root.id()).orElseThrow());
    assertEquals(root.id(), store.find(sessionId).orElseThrow().leafEntryId());

    SessionEntry stale = entry(id(), sessionId, root.id(), null);
    assertThrows(SessionLeafConflictException.class, () -> store.append(stale, root.id(), 0L));
    assertTrue(store.find(sessionId, stale.id()).isEmpty());

    SessionEntry mismatchedParent = entry(id(), sessionId, null, null);
    assertThrows(
        InvalidSessionTreeException.class, () -> store.append(mismatchedParent, root.id(), 1L));

    long otherSessionId = id();
    store.create(Session.root(otherSessionId, null, null, false, NOW));
    SessionEntry otherRoot = entry(id(), otherSessionId, null, null);
    store.append(otherRoot, null, 0L);
    SessionEntry crossSessionParent = entry(id(), sessionId, otherRoot.id(), null);
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.append(crossSessionParent, otherRoot.id(), 1L));
    assertTrue(store.find(sessionId, crossSessionParent.id()).isEmpty());
  }

  /** Entry insert 与 leaf CAS 之间发生并发版本变化时，整个 append 事务必须回滚。 */
  @Test
  void shouldRollbackInsertedEntryWhenLeafCasLosesRace() {
    long sessionId = id();
    Session original = Session.root(sessionId, null, null, false, NOW);
    store.create(original);
    SessionEntry entry = entry(id(), sessionId, null, null);
    String triggerName = "bump_harness_session_version";
    jdbcTemplate.execute("drop trigger if exists " + triggerName);
    jdbcTemplate.execute(
        "create trigger "
            + triggerName
            + " after insert on harness_session_entry for each row call '"
            + BumpSessionVersionTrigger.class.getName()
            + "'");

    try {
      assertThrows(SessionLeafConflictException.class, () -> store.append(entry, null, 0L));
    } finally {
      jdbcTemplate.execute("drop trigger if exists " + triggerName);
    }

    assertEquals(original, store.find(sessionId).orElseThrow());
    assertTrue(store.find(sessionId, entry.id()).isEmpty());
  }

  /** Checkout CAS 必须区分 stale version、stale leaf、未知目标及合法清空 leaf。 */
  @Test
  void shouldCompareAndSetOnlyExistingSameSessionLeaves() {
    long sessionId = id();
    store.create(Session.root(sessionId, null, null, false, NOW));
    SessionEntry root = entry(id(), sessionId, null, null);
    store.append(root, null, 0L);

    assertFalse(store.compareAndSetLeaf(sessionId, root.id(), 0L, root.id()));
    assertFalse(store.compareAndSetLeaf(sessionId, null, 1L, root.id()));
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.compareAndSetLeaf(sessionId, root.id(), 1L, id()));
    assertThrows(
        IllegalArgumentException.class, () -> store.compareAndSetLeaf(id(), null, 0L, null));

    assertTrue(store.compareAndSetLeaf(sessionId, root.id(), 1L, null));
    Session cleared = store.find(sessionId).orElseThrow();
    assertNull(cleared.leafEntryId());
    assertEquals(2L, cleared.version());
  }

  /** Path 与 children 查询必须保持根到叶顺序、null-parent 语义和 Session 隔离。 */
  @Test
  void shouldLoadDeepPathsAndListOnlyDirectSameSessionChildren() {
    long sessionId = id();
    store.create(Session.root(sessionId, null, null, false, NOW));
    SessionEntry root = entry(id(), sessionId, null, null);
    store.append(root, null, 0L);
    SessionEntry first = entry(id(), sessionId, root.id(), null);
    store.append(first, root.id(), 1L);
    assertTrue(store.compareAndSetLeaf(sessionId, first.id(), 2L, root.id()));
    SessionEntry second = entry(id(), sessionId, root.id(), null);
    store.append(second, root.id(), 3L);
    SessionEntry leaf = entry(id(), sessionId, second.id(), null);
    store.append(leaf, second.id(), 4L);

    assertEquals(List.of(root, second, leaf), store.loadPath(sessionId, leaf.id()));
    assertEquals(List.of(root), store.listChildren(sessionId, null));
    assertEquals(List.of(first, second), store.listChildren(sessionId, root.id()));
    assertEquals(List.of(leaf), store.listChildren(sessionId, second.id()));
    assertTrue(store.listChildren(sessionId, id()).isEmpty());
    assertTrue(store.find(sessionId, id()).isEmpty());

    long otherSessionId = id();
    store.create(Session.root(otherSessionId, null, null, false, NOW));
    SessionEntry otherRoot = entry(id(), otherSessionId, null, null);
    store.append(otherRoot, null, 0L);
    assertTrue(store.find(sessionId, otherRoot.id()).isEmpty());
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.compareAndSetLeaf(sessionId, leaf.id(), 5L, otherRoot.id()));
  }

  /** 持久化数据断链或成环时，loadPath 必须明确拒绝而不是返回部分路径。 */
  @Test
  void shouldRejectBrokenAndCyclicPersistedPaths() {
    long sessionId = id();
    store.create(Session.root(sessionId, null, null, false, NOW));
    long missingParent = id();
    long brokenLeaf = id();
    insertRawEntry(brokenLeaf, sessionId, missingParent);

    assertThrows(InvalidSessionTreeException.class, () -> store.loadPath(sessionId, brokenLeaf));
    assertThrows(InvalidSessionTreeException.class, () -> store.loadPath(sessionId, id()));

    long cycleA = id();
    long cycleB = id();
    insertRawEntry(cycleA, sessionId, cycleB);
    insertRawEntry(cycleB, sessionId, cycleA);
    assertThrows(InvalidSessionTreeException.class, () -> store.loadPath(sessionId, cycleA));
  }

  private void insertRawEntry(long id, long sessionId, Long parentEntryId) {
    jdbcTemplate.update(
        "insert into harness_session_entry "
            + "(id, session_id, parent_entry_id, run_id, entry_type, payload_json, gmt_create) "
            + "values (?, ?, ?, null, 'agent_snapshot', ?, ?)",
        id,
        sessionId,
        parentEntryId,
        SNAPSHOT_JSON,
        NOW);
  }

  private static Session child(
      long id, long parentSessionId, long rootSessionId, Long leafEntryId, int depth) {
    return new Session(
        id,
        9L,
        "child",
        leafEntryId,
        null,
        parentSessionId,
        rootSessionId,
        100L,
        depth,
        false,
        0,
        NOW,
        NOW);
  }

  private static SessionEntry entry(long id, long sessionId, Long parentEntryId, Long runId) {
    AgentSnapshotEntryPayload payload =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    return new SessionEntry(id, sessionId, parentEntryId, runId, payload.type(), payload, NOW);
  }

  public static final class BumpSessionVersionTrigger implements Trigger {
    @Override
    public void init(
        Connection connection,
        String schemaName,
        String triggerName,
        String tableName,
        boolean before,
        int type) {}

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "update harness_session set version = version + 1 where id = ?")) {
        statement.setLong(1, ((Number) newRow[1]).longValue());
        statement.executeUpdate();
      }
    }

    @Override
    public void close() {}

    @Override
    public void remove() {}
  }

  private static long id() {
    return IDS.incrementAndGet();
  }
}
