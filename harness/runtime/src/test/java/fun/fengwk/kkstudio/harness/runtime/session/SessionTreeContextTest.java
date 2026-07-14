package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.context.ContextState;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionTreeContextTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** sibling A -> sibling B -> sibling A checkout 必须保持单 leaf CAS，并能列出两个分支。 */
  @Test
  void shouldCheckoutSiblingBranchesAndListChildren() {
    InMemoryStore store = new InMemoryStore();
    SessionTree tree = tree(store);
    Session session = tree.create(1L, 10L, "root", true);
    SessionEntry snapshot = append(tree, session.id(), null, snapshotPayload());
    SessionEntry branchA = append(tree, session.id(), snapshot.id(), user("A"));

    assertTrue(tree.checkout(session.id(), branchA.id(), snapshot.id()));
    SessionEntry branchB = append(tree, session.id(), snapshot.id(), user("B"));
    assertTrue(tree.checkout(session.id(), branchB.id(), branchA.id()));

    assertEquals(branchA.id(), store.find(session.id()).orElseThrow().leafEntryId());
    assertEquals(
        List.of(branchA.id(), branchB.id()),
        tree.listChildren(session.id(), snapshot.id()).stream().map(SessionEntry::id).toList());
  }

  /** checkout 必须拒绝跨 Session、断链和循环目标。 */
  @Test
  void shouldRejectInvalidCheckoutTargets() {
    InMemoryStore store = new InMemoryStore();
    SessionTree tree = tree(store);
    Session first = tree.create(1L, null, null, false);
    Session second = tree.create(1L, null, null, false);
    SessionEntry firstRoot = append(tree, first.id(), null, snapshotPayload());
    SessionEntry secondRoot = append(tree, second.id(), null, snapshotPayload());

    assertThrows(
        InvalidSessionTreeException.class,
        () -> tree.checkout(first.id(), firstRoot.id(), secondRoot.id()));

    long missingParent = 999_999L;
    SessionEntry broken = entry(800L, first.id(), missingParent, user("broken"));
    store.putEntry(broken);
    assertThrows(
        InvalidSessionTreeException.class,
        () -> tree.checkout(first.id(), firstRoot.id(), broken.id()));

    SessionEntry cycleA = entry(801L, first.id(), 802L, user("cycle-a"));
    SessionEntry cycleB = entry(802L, first.id(), 801L, user("cycle-b"));
    store.putEntry(cycleA);
    store.putEntry(cycleB);
    assertThrows(
        InvalidSessionTreeException.class,
        () -> tree.checkout(first.id(), firstRoot.id(), cycleA.id()));
  }

  /** fork 复制 active path，使用新 bigint Entry id，并冻结 child hierarchy 字段。 */
  @Test
  void shouldForkWithIndependentEntriesAndHierarchy() {
    InMemoryStore store = new InMemoryStore();
    SessionTree tree = tree(store);
    Session source = tree.create(7L, 11L, "source", true);
    SessionEntry snapshot = append(tree, source.id(), null, snapshotPayload());
    SessionEntry leaf = append(tree, source.id(), snapshot.id(), user("message"));

    Session fork = tree.fork(source.id(), leaf.id());
    List<SessionEntry> clone = store.loadPath(fork.id(), fork.leafEntryId());

    assertEquals(source.id(), fork.parentSessionId());
    assertEquals(source.id(), fork.rootSessionId());
    assertEquals(1, fork.depth());
    assertEquals(source.workspaceId(), fork.workspaceId());
    assertFalse(fork.yoloEnabled());
    assertEquals(2, clone.size());
    assertNotEquals(snapshot.id(), clone.get(0).id());
  }

  /** 过期 leaf/version 的 append 被拒绝且不会改变当前 leaf。 */
  @Test
  void shouldRejectStaleLeafCompareAndSet() {
    InMemoryStore store = new InMemoryStore();
    SessionTree tree = tree(store);
    Session session = tree.create(1L, null, null, false);
    SessionEntry first = append(tree, session.id(), null, snapshotPayload());

    assertFalse(tree.append(session.id(), null, new SessionEntryDraft(user("stale"))).appended());
    assertEquals(first.id(), store.find(session.id()).orElseThrow().leafEntryId());
  }

  /** 编解码覆盖 Artifact preview，并拒绝坏 payload 和字符串 compaction id。 */
  @Test
  void shouldRoundTripPayloadAndRejectMalformedPayload() {
    SessionEntryJsonCodec codec = new SessionEntryJsonCodec();
    AgentMessage toolMessage =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "read",
                    List.of(
                        new TextMessageContent("preview"),
                        new ArtifactMessageContent("artifact-1", "text/plain", "first line")),
                    false,
                    "{}")));
    String json = codec.encode(new MessageEntryPayload(toolMessage));

    assertEquals(
        new MessageEntryPayload(toolMessage), codec.decode(SessionEntryType.MESSAGE, json));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                SessionEntryType.MESSAGE, "{\"message\":{\"role\":\"USER\",\"contents\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(SessionEntryType.LABEL, "{\"label\":\"x\",\"unknown\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                SessionEntryType.COMPACTION,
                "{\"summary\":\"x\",\"firstKeptEntryId\":\"2\",\"tokensBefore\":1,\"detailsJson\":\"{}\"}"));
  }

  /** ToolResult 必须携带工具名，TOOL 消息必须恰好包含一个结果。 */
  @Test
  void shouldEnforceToolResultAndToolMessageContracts() {
    ToolResultMessageContent result = toolResult("read");

    assertEquals("read", result.toolName());
    assertThrows(IllegalArgumentException.class, () -> toolResult(null));
    assertThrows(IllegalArgumentException.class, () -> toolResult(" "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentMessage(AgentMessageRole.TOOL, List.of(new TextMessageContent("mixed"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentMessage(
                AgentMessageRole.TOOL, List.of(result, new TextMessageContent("mixed"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentMessage(AgentMessageRole.TOOL, List.of(result, result)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentMessage(AgentMessageRole.USER, List.of(result)));
  }

  /** ToolCall 和 ToolResult 独立持久化往返后必须保留相同的调用 ID 与工具名。 */
  @Test
  void shouldPreserveToolIdentityAcrossMessageRoundTrips() {
    SessionEntryJsonCodec codec = new SessionEntryJsonCodec();
    AgentMessage callMessage =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "read", "{\"path\":\"README.md\"}")));
    AgentMessage resultMessage =
        new AgentMessage(AgentMessageRole.TOOL, List.of(toolResult("read")));

    AgentMessage decodedCall =
        ((MessageEntryPayload)
                codec.decode(
                    SessionEntryType.MESSAGE,
                    codec.encode(new MessageEntryPayload(callMessage, assistantMetadata()))))
            .message();
    AgentMessage decodedResult =
        ((MessageEntryPayload)
                codec.decode(
                    SessionEntryType.MESSAGE, codec.encode(new MessageEntryPayload(resultMessage))))
            .message();
    ToolCallMessageContent call = (ToolCallMessageContent) decodedCall.contents().get(0);
    ToolResultMessageContent result = (ToolResultMessageContent) decodedResult.contents().get(0);

    assertEquals("call-1", result.toolCallId());
    assertEquals(call.toolCallId(), result.toolCallId());
    assertEquals(call.toolName(), result.toolName());
  }

  /** JSON 边界必须拒绝缺失 toolName 以及包含多个 ToolResult 的 TOOL 消息。 */
  @Test
  void shouldRejectMalformedToolResultPayloads() {
    SessionEntryJsonCodec codec = new SessionEntryJsonCodec();
    String missingToolName =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"call-1\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"error\":false,\"detailsJson\":\"{}\"}]},\"assistantMetadata\":null}";
    String result =
        "{\"type\":\"tool_result\",\"toolCallId\":\"call-1\",\"toolName\":\"read\","
            + "\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],\"error\":false,"
            + "\"detailsJson\":\"{}\"}";
    String multipleResults =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":["
            + result
            + ","
            + result
            + "]},\"assistantMetadata\":null}";

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(SessionEntryType.MESSAGE, missingToolName));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(SessionEntryType.MESSAGE, multipleResults));
  }

  /** latest compaction = summary + 其祖先 firstKept..前一项 + compaction 后新 entries。 */
  @Test
  void shouldBuildContextFromLatestCompactionAncestorBoundary() {
    InMemoryStore store = new InMemoryStore();
    long sessionId = 1L;
    List<SessionEntry> path =
        List.of(
            entry(1L, sessionId, null, snapshotPayload()),
            entry(2L, sessionId, 1L, user("discarded")),
            entry(3L, sessionId, 2L, user("old-kept")),
            entry(4L, sessionId, 3L, new CompactionEntryPayload("old", 3L, 20, "{}")),
            entry(5L, sessionId, 4L, user("latest-kept-1")),
            entry(6L, sessionId, 5L, user("latest-kept-2")),
            entry(7L, sessionId, 6L, new CompactionEntryPayload("latest", 5L, 40, "{}")),
            entry(8L, sessionId, 7L, new CustomEntryPayload("trace", "{}")),
            entry(
                9L,
                sessionId,
                8L,
                new CustomMessageEntryPayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("custom"))))),
            entry(10L, sessionId, 9L, user("after")),
            entry(11L, sessionId, 10L, new ModelChangeEntryPayload("model-2", "fast")));
    store.put(session(sessionId, 11L), path);

    SessionContext context = builder(store).build(sessionId);

    assertEquals("model-2", context.config().modelId());
    assertEquals("fast", context.config().variant());
    assertEquals(
        List.of(
            "system",
            "Session summary:\nlatest",
            "latest-kept-1",
            "latest-kept-2",
            "custom",
            "after"),
        texts(context));
  }

  /** 非祖先 firstKept 的最新 compaction 无效，回退到上一个有效 compaction 且不投影坏摘要。 */
  @Test
  void shouldIgnoreCompactionWithNonAncestorFirstKept() {
    InMemoryStore store = new InMemoryStore();
    long sessionId = 2L;
    List<SessionEntry> path =
        List.of(
            entry(21L, sessionId, null, snapshotPayload()),
            entry(22L, sessionId, 21L, user("kept")),
            entry(23L, sessionId, 22L, new CompactionEntryPayload("valid", 22L, 10, "{}")),
            entry(24L, sessionId, 23L, user("between")),
            entry(25L, sessionId, 24L, new CompactionEntryPayload("invalid", 99L, 30, "{}")),
            entry(26L, sessionId, 25L, user("after")));
    store.put(session(sessionId, 26L), path);

    assertEquals(
        List.of("system", "Session summary:\nvalid", "kept", "between", "after"),
        texts(builder(store).build(sessionId)));
  }

  /** 仅存在非法 self/future 边界时不应用 compaction，也不把非法摘要送入模型。 */
  @Test
  void shouldOmitInvalidCompactionsWithoutValidFallback() {
    InMemoryStore store = new InMemoryStore();
    long sessionId = 3L;
    List<SessionEntry> path =
        List.of(
            entry(31L, sessionId, null, snapshotPayload()),
            entry(32L, sessionId, 31L, user("before")),
            entry(33L, sessionId, 32L, new CompactionEntryPayload("self", 33L, 10, "{}")),
            entry(34L, sessionId, 33L, new CompactionEntryPayload("future", 35L, 20, "{}")),
            entry(35L, sessionId, 34L, user("after")));
    store.put(session(sessionId, 35L), path);

    assertEquals(List.of("system", "before", "after"), texts(builder(store).build(sessionId)));
  }

  private static SessionTree tree(InMemoryStore store) {
    return new SessionTree(store, store, new SequenceIds(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static SessionContextBuilder builder(InMemoryStore store) {
    return new SessionContextBuilder(
        store, store, new DefaultContextTransform(), List.of(SessionTreeContextTest::identity));
  }

  private static ContextState identity(ContextState state) {
    return state;
  }

  private static List<String> texts(SessionContext context) {
    return context.messages().stream()
        .map(message -> ((TextMessageContent) message.contents().get(0)).text())
        .toList();
  }

  private static SessionEntry append(
      SessionTree tree, long sessionId, Long expectedLeaf, SessionEntryPayload payload) {
    AppendResult result = tree.append(sessionId, expectedLeaf, new SessionEntryDraft(payload));
    assertTrue(result.appended());
    return result.entry();
  }

  private static AgentSnapshot snapshot() {
    return new AgentSnapshot(
        "system", "model-1", "default", List.of("read"), List.of(), List.of(), "{}");
  }

  private static AgentSnapshotEntryPayload snapshotPayload() {
    return new AgentSnapshotEntryPayload(snapshot());
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.TOOL_CALLS,
        new ModelUsage(1, 1, 0, 0, 0),
        new ModelCost("USD", BigDecimal.ZERO));
  }

  private static MessageEntryPayload user(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  private static ToolResultMessageContent toolResult(String toolName) {
    return new ToolResultMessageContent(
        "call-1", toolName, List.of(new TextMessageContent("ok")), false, "{}");
  }

  private static SessionEntry entry(
      long id, long sessionId, Long parent, SessionEntryPayload payload) {
    return new SessionEntry(id, sessionId, parent, null, payload.type(), payload, NOW);
  }

  private static Session session(long id, Long leafEntryId) {
    return new Session(
        id, 1L, 10L, "test", leafEntryId, null, null, id, null, 0, false, 0, NOW, NOW);
  }

  private static final class SequenceIds implements SessionIdGenerator {
    private long session = 100L;
    private long entry = 1_000L;

    @Override
    public long newSessionId() {
      return ++session;
    }

    @Override
    public long newEntryId() {
      return ++entry;
    }
  }

  private static final class InMemoryStore implements SessionStore, SessionEntryStore {
    private final Map<Long, Session> sessions = new HashMap<>();
    private final Map<Long, SessionEntry> entries = new HashMap<>();

    @Override
    public Optional<Session> find(long sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void create(Session session) {
      if (sessions.putIfAbsent(session.id(), session) != null) {
        throw new IllegalArgumentException("duplicate session");
      }
    }

    @Override
    public void createFork(Session session, List<SessionEntry> clone) {
      sessions.put(session.id(), session);
      clone.forEach(entry -> entries.put(entry.id(), entry));
    }

    @Override
    public void append(SessionEntry entry, Long expectedLeafEntryId, long expectedSessionVersion) {
      Session session = sessions.get(entry.sessionId());
      if (session == null
          || session.version() != expectedSessionVersion
          || !Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
        throw new SessionLeafConflictException(entry.sessionId(), expectedLeafEntryId);
      }
      entries.put(entry.id(), entry);
      sessions.put(session.id(), withLeaf(session, entry.id()));
    }

    @Override
    public boolean compareAndSetLeaf(
        long sessionId,
        Long expectedLeafEntryId,
        long expectedSessionVersion,
        Long newLeafEntryId) {
      Session session = sessions.get(sessionId);
      if (session == null
          || session.version() != expectedSessionVersion
          || !Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
        return false;
      }
      sessions.put(sessionId, withLeaf(session, newLeafEntryId));
      return true;
    }

    @Override
    public Optional<SessionEntry> find(long sessionId, long entryId) {
      SessionEntry entry = entries.get(entryId);
      return entry != null && entry.sessionId() == sessionId
          ? Optional.of(entry)
          : Optional.empty();
    }

    @Override
    public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
      List<SessionEntry> result = new ArrayList<>();
      Set<Long> seen = new HashSet<>();
      Long id = leafEntryId;
      while (id != null) {
        if (!seen.add(id)) {
          throw new InvalidSessionTreeException("cycle");
        }
        SessionEntry entry =
            find(sessionId, id).orElseThrow(() -> new InvalidSessionTreeException("missing entry"));
        result.add(entry);
        id = entry.parentEntryId();
      }
      Collections.reverse(result);
      return result;
    }

    @Override
    public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
      return entries.values().stream()
          .filter(
              entry ->
                  entry.sessionId() == sessionId
                      && Objects.equals(entry.parentEntryId(), parentEntryId))
          .sorted((left, right) -> Long.compare(left.id(), right.id()))
          .toList();
    }

    void put(Session session, List<SessionEntry> path) {
      sessions.put(session.id(), session);
      path.forEach(entry -> entries.put(entry.id(), entry));
    }

    void putEntry(SessionEntry entry) {
      entries.put(entry.id(), entry);
    }

    private Session withLeaf(Session session, Long leafEntryId) {
      return new Session(
          session.id(),
          session.workspaceId(),
          session.agentDefinitionId(),
          session.title(),
          leafEntryId,
          session.activeRunId(),
          session.parentSessionId(),
          session.rootSessionId(),
          session.parentInvocationId(),
          session.depth(),
          session.yoloEnabled(),
          session.version() + 1,
          session.createdAt(),
          NOW);
    }
  }
}
