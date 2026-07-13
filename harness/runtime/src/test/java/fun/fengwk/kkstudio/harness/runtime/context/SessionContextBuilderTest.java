package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.BranchSummaryEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.LabelEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionContextBuilderTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final long SESSION_ID = 11L;

  /** 未知 Session 与尚无 active leaf 的 Session 必须在读取路径前失败。 */
  @Test
  void shouldRejectUnknownSessionAndSessionWithoutLeaf() {
    FixedStores unknown = new FixedStores(null, List.of());
    IllegalArgumentException unknownException =
        assertThrows(IllegalArgumentException.class, () -> builder(unknown, List.of()).build(99L));

    FixedStores noLeaf = new FixedStores(session(null), List.of());
    ContextProjectionException noLeafException =
        assertThrows(
            ContextProjectionException.class, () -> builder(noLeaf, List.of()).build(SESSION_ID));

    assertEquals("unknown session: 99", unknownException.getMessage());
    assertEquals("session has no active leaf", noLeafException.getMessage());
    assertEquals(0, unknown.loadPathCalls);
    assertEquals(0, noLeaf.loadPathCalls);
  }

  /** 空白 system prompt 不投影；branch summary/custom message 进入，label/custom 状态不进入。 */
  @Test
  void shouldProjectSemanticMessagesAndExcludeStateOnlyEntries() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, snapshot("   ")),
            entry(2L, 1L, message("user")),
            entry(3L, 2L, new BranchSummaryEntryPayload("forked work")),
            entry(4L, 3L, new LabelEntryPayload("checkpoint")),
            entry(5L, 4L, new CustomEntryPayload("trace", "{}")),
            entry(6L, 5L, new CustomMessageEntryPayload(agentMessage("custom"))));
    FixedStores stores = new FixedStores(session(6L), path);

    SessionContext context = builder(stores, List.of()).build(SESSION_ID);

    assertEquals(List.of("user", "Branch summary:\nforked work", "custom"), messageTexts(context));
    assertEquals(
        List.of(AgentMessageRole.USER, AgentMessageRole.SYSTEM, AgentMessageRole.USER),
        context.messages().stream().map(AgentMessage::role).toList());
    assertEquals(1, stores.loadPathCalls);
  }

  /** null system prompt 同样不生成系统消息，但配置与普通消息仍完整保留。 */
  @Test
  void shouldAllowNullSystemPrompt() {
    List<SessionEntry> path =
        List.of(entry(1L, null, snapshot(null)), entry(2L, 1L, message("hello")));
    FixedStores stores = new FixedStores(session(2L), path);

    SessionContext context = builder(stores, List.of()).build(SESSION_ID);

    assertNull(context.config().systemPrompt());
    assertEquals(List.of("hello"), messageTexts(context));
  }

  /** Extension 必须按 priority 升序执行，并把前一扩展的结果传给后一扩展。 */
  @Test
  void shouldApplyExtensionsByPriority() {
    List<String> order = new ArrayList<>();
    ContextTransform later = transform(20, "later", order, "final-model");
    ContextTransform earlier = transform(-10, "earlier", order, "intermediate-model");
    List<SessionEntry> path =
        List.of(entry(1L, null, snapshot("system")), entry(2L, 1L, message("hello")));
    FixedStores stores = new FixedStores(session(2L), path);

    SessionContext context = builder(stores, List.of(later, earlier)).build(SESSION_ID);

    assertEquals(List.of("earlier", "later"), order);
    assertEquals("final-model", context.config().modelId());
    assertEquals(List.of("system", "hello"), messageTexts(context));
  }

  /** Extension 返回 null 会破坏流水线，必须在该扩展处立即失败。 */
  @Test
  void shouldRejectNullExtensionResult() {
    List<SessionEntry> path = List.of(entry(1L, null, snapshot("system")));
    FixedStores stores = new FixedStores(session(1L), path);
    ContextTransform nullTransform = state -> null;

    NullPointerException exception =
        assertThrows(
            NullPointerException.class,
            () -> builder(stores, List.of(nullTransform)).build(SESSION_ID));

    assertEquals("context transform result", exception.getMessage());
  }

  private static ContextTransform transform(
      int priority, String name, List<String> order, String modelId) {
    return new ContextTransform() {
      @Override
      public ContextState transform(ContextState state) {
        order.add(name);
        return new ContextState(
            state.config().withModel(modelId, state.config().variant()), state.entries());
      }

      @Override
      public int priority() {
        return priority;
      }
    };
  }

  private static SessionContextBuilder builder(
      FixedStores stores, List<ContextTransform> transforms) {
    return new SessionContextBuilder(stores, stores, new DefaultContextTransform(), transforms);
  }

  private static List<String> messageTexts(SessionContext context) {
    return context.messages().stream()
        .map(message -> ((TextMessageContent) message.contents().get(0)).text())
        .toList();
  }

  private static Session session(Long leafEntryId) {
    return new Session(
        SESSION_ID,
        1L,
        2L,
        "session",
        leafEntryId,
        null,
        null,
        SESSION_ID,
        null,
        0,
        false,
        0,
        NOW,
        NOW);
  }

  private static AgentSnapshotEntryPayload snapshot(String systemPrompt) {
    return new AgentSnapshotEntryPayload(
        new AgentSnapshot(
            systemPrompt,
            "base-model",
            "default",
            List.of("read"),
            List.of("java"),
            List.of("explorer"),
            "{}"));
  }

  private static MessageEntryPayload message(String text) {
    return new MessageEntryPayload(agentMessage(text));
  }

  private static AgentMessage agentMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static SessionEntry entry(long id, Long parentId, SessionEntryPayload payload) {
    return new SessionEntry(id, SESSION_ID, parentId, null, payload.type(), payload, NOW);
  }

  private static final class FixedStores implements SessionStore, SessionEntryStore {
    private final Session session;
    private final List<SessionEntry> path;
    private int loadPathCalls;

    private FixedStores(Session session, List<SessionEntry> path) {
      this.session = session;
      this.path = List.copyOf(path);
    }

    @Override
    public Optional<Session> find(long sessionId) {
      return session != null && session.id() == sessionId ? Optional.of(session) : Optional.empty();
    }

    @Override
    public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
      loadPathCalls++;
      assertEquals(SESSION_ID, sessionId);
      assertEquals(session.leafEntryId(), leafEntryId);
      return path;
    }

    @Override
    public void create(Session session) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void createFork(Session session, List<SessionEntry> entries) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void append(SessionEntry entry, Long expectedLeafEntryId, long expectedSessionVersion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean compareAndSetLeaf(
        long sessionId,
        Long expectedLeafEntryId,
        long expectedSessionVersion,
        Long newLeafEntryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SessionEntry> find(long sessionId, long entryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
      throw new UnsupportedOperationException();
    }
  }
}
