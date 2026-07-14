package fun.fengwk.kkstudio.core.harness.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.InvalidSessionTreeException;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionLeafConflictException;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class MysqlHarnessSessionStoreTest {
  private static final AtomicLong IDS = new AtomicLong(8_000_000L);

  @Autowired private MysqlHarnessSessionStore store;
  @Autowired private SnowflakeSessionIdGenerator idGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** H2 adapter 的 leaf/version CAS 冲突回滚不会留下可被读取的孤儿 Entry。 */
  @Test
  void shouldAppendWithLeafAndVersionCasAndRollbackConflict() {
    long sessionId = id();
    store.create(root(sessionId, 1L));
    SessionEntry root = entry(id(), sessionId, null);
    store.append(root, null, 0L);

    Session afterRoot = store.find(sessionId).orElseThrow();
    SessionEntry current = entry(id(), sessionId, root.id());
    store.append(current, root.id(), afterRoot.version());
    SessionEntry stale = entry(id(), sessionId, root.id());

    assertThrows(
        SessionLeafConflictException.class,
        () -> store.append(stale, root.id(), afterRoot.version()));
    assertEquals(current.id(), store.find(sessionId).orElseThrow().leafEntryId());
    assertFalse(store.find(sessionId, stale.id()).isPresent());
  }

  /** ToolCall 与 ToolResult 经数据库 payload 往返后保留相同工具标识。 */
  @Test
  void shouldRoundTripToolNameThroughEntryStore() {
    long sessionId = id();
    store.create(root(sessionId, 1L));
    SessionEntry snapshot = entry(id(), sessionId, null);
    store.append(snapshot, null, 0L);
    SessionEntry call =
        entry(
            id(),
            sessionId,
            snapshot.id(),
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("call-1", "read", "{}"))),
                new AssistantMessageMetadata(
                    ProviderStopReason.TOOL_CALLS,
                    new ModelUsage(1, 1, 0, 0, 0),
                    new ModelCost("USD", BigDecimal.ZERO))));
    store.append(call, snapshot.id(), 1L);
    SessionEntry result =
        entry(
            id(),
            sessionId,
            call.id(),
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "read",
                            List.of(new TextMessageContent("ok")),
                            false,
                            "{}")))));
    store.append(result, call.id(), 2L);

    List<SessionEntry> path = store.loadPath(sessionId, result.id());
    ToolCallMessageContent storedCall =
        (ToolCallMessageContent)
            ((MessageEntryPayload) path.get(1).payload()).message().contents().get(0);
    ToolResultMessageContent storedResult =
        (ToolResultMessageContent)
            ((MessageEntryPayload) path.get(2).payload()).message().contents().get(0);

    assertEquals(storedCall.toolCallId(), storedResult.toolCallId());
    assertEquals(storedCall.toolName(), storedResult.toolName());
  }

  /** 同 Session children query 返回 sibling，且跨 Session Entry 不可作为 checkout/path 目标。 */
  @Test
  void shouldListChildrenAndRejectCrossSessionPath() {
    long sourceId = id();
    long otherId = id();
    store.create(root(sourceId, 1L));
    store.create(root(otherId, 1L));
    SessionEntry sourceRoot = entry(id(), sourceId, null);
    store.append(sourceRoot, null, 0L);
    SessionEntry first = entry(id(), sourceId, sourceRoot.id());
    store.append(first, sourceRoot.id(), 1L);
    assertTrue(store.compareAndSetLeaf(sourceId, first.id(), 2L, sourceRoot.id()));
    SessionEntry second = entry(id(), sourceId, sourceRoot.id());
    store.append(second, sourceRoot.id(), 3L);

    assertEquals(
        List.of(first.id(), second.id()),
        store.listChildren(sourceId, sourceRoot.id()).stream().map(SessionEntry::id).toList());
    assertThrows(InvalidSessionTreeException.class, () -> store.loadPath(otherId, sourceRoot.id()));
    assertThrows(
        InvalidSessionTreeException.class,
        () -> store.compareAndSetLeaf(sourceId, second.id(), 4L, id()));
  }

  /** Child 必须继承 root/workspace 并使用 parent.depth + 1，跨 Workspace 或错误 root 均拒绝。 */
  @Test
  void shouldValidateChildSessionHierarchy() {
    long sourceId = id();
    store.create(root(sourceId, 9L));

    Session crossWorkspace = child(id(), 10L, sourceId, sourceId, 1);
    assertThrows(
        InvalidSessionTreeException.class, () -> store.createFork(crossWorkspace, List.of()));
    Session wrongRoot = child(id(), 9L, sourceId, id(), 1);
    assertThrows(InvalidSessionTreeException.class, () -> store.createFork(wrongRoot, List.of()));
    Session wrongDepth = child(id(), 9L, sourceId, sourceId, 2);
    assertThrows(InvalidSessionTreeException.class, () -> store.createFork(wrongDepth, List.of()));

    Session valid = child(id(), 9L, sourceId, sourceId, 1);
    store.createFork(valid, List.of());
    Session loaded = store.find(valid.id()).orElseThrow();
    assertEquals(sourceId, loaded.parentSessionId());
    assertEquals(sourceId, loaded.rootSessionId());
    assertEquals(1, loaded.depth());
  }

  /** 新表只有单 bigint id，Session 字段完整，Entry 不含双 ID、version 或 updatedAt。 */
  @Test
  void shouldUseFinalSessionAndEntryColumns() {
    Set<String> sessionColumns = columns("harness_session");
    Set<String> entryColumns = columns("harness_session_entry");

    assertTrue(
        sessionColumns.containsAll(
            Set.of(
                "id",
                "workspace_id",
                "agent_definition_id",
                "title",
                "leaf_entry_id",
                "active_run_id",
                "parent_session_id",
                "root_session_id",
                "parent_invocation_id",
                "depth",
                "yolo_enabled",
                "version",
                "gmt_create",
                "gmt_modified")));
    assertEquals(
        Set.of(
            "id",
            "session_id",
            "parent_entry_id",
            "run_id",
            "entry_type",
            "payload_json",
            "gmt_create"),
        entryColumns);
    assertFalse(sessionColumns.contains("session_id"));
    assertFalse(entryColumns.contains("entry_id"));
  }

  /** 旧 Run/Event 噪声不参与新 Context path 查询。 */
  @Test
  void shouldLoadOnlyTargetEntryPathWithTenThousandLegacyEvents() {
    jdbcTemplate.update(
        """
        insert into agent_session_event (
            id, event_id, session_id, parent_event_id, run_id, event_type, payload_json,
            gmt_create, gmt_modified, version
        )
        select 900000000 + n, concat('noise-', n), 'noise-session', 'root', null,
               'assistant_delta', '{}', current_timestamp, current_timestamp, 0
        from system_range(1, 10000) as noise(n)
        """);
    long sessionId = id();
    store.create(root(sessionId, 1L));
    SessionEntry root = entry(id(), sessionId, null);
    store.append(root, null, 0L);

    List<SessionEntry> path = store.loadPath(sessionId, root.id());
    assertEquals(1, path.size());
    assertEquals(root.id(), path.get(0).id());
    assertEquals(root.payload(), path.get(0).payload());
  }

  /** Runtime 的 SessionIdGenerator 适配为单 Snowflake bigint。 */
  @Test
  void shouldGenerateDistinctSnowflakeIds() {
    long sessionId = idGenerator.newSessionId();
    long nextSessionId = idGenerator.newSessionId();
    long entryId = idGenerator.newEntryId();
    long nextEntryId = idGenerator.newEntryId();
    assertTrue(sessionId > 0);
    assertTrue(entryId > 0);
    assertNotEquals(sessionId, nextSessionId);
    assertNotEquals(entryId, nextEntryId);
  }

  private Set<String> columns(String table) {
    return Set.copyOf(
        jdbcTemplate.queryForList(
            "select column_name from information_schema.columns where table_name = ?",
            String.class,
            table));
  }

  private static Session root(long id, long workspaceId) {
    Instant now = Instant.now();
    return Session.root(id, workspaceId, 1L, "root", true, now);
  }

  private static Session child(
      long id, long workspaceId, long parentSessionId, long rootSessionId, int depth) {
    Instant now = Instant.now();
    return new Session(
        id,
        workspaceId,
        1L,
        "child",
        null,
        null,
        parentSessionId,
        rootSessionId,
        null,
        depth,
        false,
        0,
        now,
        now);
  }

  private static SessionEntry entry(long entryId, long sessionId, Long parentEntryId) {
    AgentSnapshot snapshot =
        new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}");
    return entry(entryId, sessionId, parentEntryId, new AgentSnapshotEntryPayload(snapshot));
  }

  private static SessionEntry entry(
      long entryId, long sessionId, Long parentEntryId, SessionEntryPayload payload) {
    return new SessionEntry(
        entryId, sessionId, parentEntryId, null, payload.type(), payload, Instant.now());
  }

  private static long id() {
    return IDS.incrementAndGet();
  }
}
