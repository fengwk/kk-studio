package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.runtime.context.ContextState;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
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

  /** 覆盖回退到祖先后追加分支，以及 fork 时不跨 Session 复用 Entry。 */
  @Test
  void shouldNavigateBranchAndForkWithIndependentEntries() {
    InMemoryStore store = new InMemoryStore();
    SequenceIds ids = new SequenceIds();
    SessionTree tree = new SessionTree(store, store, ids, Clock.fixed(NOW, ZoneOffset.UTC));
    tree.create("session-1", 1L);

    SessionEntry snapshot =
        append(tree, "session-1", null, new AgentSnapshotEntryPayload(snapshot()));
    SessionEntry original = append(tree, "session-1", snapshot.entryId(), user("original"));
    assertTrue(tree.navigate("session-1", original.entryId(), snapshot.entryId()));
    SessionEntry branch = append(tree, "session-1", snapshot.entryId(), user("branch"));

    Session fork = tree.fork("session-1", branch.entryId());
    assertEquals("session-1", fork.parentSessionId());
    assertEquals(1L, fork.workspaceId());
    assertEquals(2, store.loadPath(fork.sessionId(), fork.leafEntryId()).size());
    assertFalse(store.loadPath(fork.sessionId(), fork.leafEntryId()).contains(snapshot));
    assertThrows(
        InvalidSessionTreeException.class,
        () -> tree.navigate("session-1", branch.entryId(), original.entryId()));
  }

  /** 覆盖过期 leaf 的 append 被拒绝且不会改变当前 leaf。 */
  @Test
  void shouldRejectStaleLeafCompareAndSet() {
    InMemoryStore store = new InMemoryStore();
    SessionTree tree =
        new SessionTree(store, store, new SequenceIds(), Clock.fixed(NOW, ZoneOffset.UTC));
    tree.create("session-1", 1L);
    SessionEntry first =
        append(tree, "session-1", null, new AgentSnapshotEntryPayload(snapshot()));

    assertFalse(tree.append("session-1", null, new SessionEntryDraft(user("stale"))).appended());
    assertEquals(first.entryId(), store.find("session-1").orElseThrow().leafEntryId());
  }

  /** 编解码覆盖带 Artifact preview 的 ToolResult，并拒绝未知或坏 JSON payload。 */
  @Test
  void shouldRoundTripPayloadAndRejectMalformedPayload() {
    SessionEntryJsonCodec codec = new SessionEntryJsonCodec();
    AgentMessage toolMessage =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    List.of(
                        new TextMessageContent("preview"),
                        new ArtifactMessageContent("artifact-1", "text/plain", "first line")),
                    false,
                    "{}")));
    String json = codec.encode(new MessageEntryPayload(toolMessage));

    assertEquals(new MessageEntryPayload(toolMessage), codec.decode(SessionEntryType.MESSAGE, json));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(SessionEntryType.MESSAGE, "{\"message\":{\"role\":\"USER\",\"contents\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(SessionEntryType.LABEL, "{\"label\":\"x\",\"unknown\":true}"));
  }

  /** 验证 compaction 仅改变投影视图，custom 不入模型而 custom_message 会入模型。 */
  @Test
  void shouldBuildCompactedContextWithCustomMessageOnly() {
    InMemoryStore store = new InMemoryStore();
    List<SessionEntry> path =
        List.of(
            entry("e1", null, new AgentSnapshotEntryPayload(snapshot())),
            entry("e2", "e1", user("discarded")),
            entry("e3", "e2", new CustomEntryPayload("trace", "{}")),
            entry("e4", "e3", new CompactionEntryPayload("history", "e5", 42, "{}")),
            entry("e5", "e4", user("kept")),
            entry(
                "e6",
                "e5",
                new CustomMessageEntryPayload(
                    new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("extension"))))),
            entry("e7", "e6", new ModelChangeEntryPayload("model-2", "fast")));
    store.put(new Session("session-1", 1L, null, "e7", NOW), path);
    SessionContextBuilder builder =
        new SessionContextBuilder(store, store, new DefaultContextTransform(), List.of(SessionTreeContextTest::identity));

    SessionContext context = builder.build("session-1");
    assertEquals("model-2", context.config().modelId());
    assertEquals("fast", context.config().variant());
    assertEquals(
        List.of("system", "Session summary:\nhistory", "kept", "extension"),
        context.messages().stream()
            .map(message -> ((TextMessageContent) message.contents().get(0)).text())
            .toList());
  }

  private static ContextState identity(ContextState state) {
    return state;
  }

  private static SessionEntry append(
      SessionTree tree, String sessionId, String expectedLeaf, SessionEntryPayload payload) {
    return tree
        .append(sessionId, expectedLeaf, new SessionEntryDraft(payload))
        .entry();
  }

  private static AgentSnapshot snapshot() {
    return new AgentSnapshot("system", "model-1", "default", List.of("read"), List.of(), List.of(), "{}");
  }

  private static MessageEntryPayload user(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  private static SessionEntry entry(String id, String parent, SessionEntryPayload payload) {
    return new SessionEntry(id, "session-1", parent, payload.type(), payload, NOW);
  }

  private static final class SequenceIds implements SessionIdGenerator {
    private int session;
    private int entry;

    @Override
    public String newSessionId() {
      return "fork-" + ++session;
    }

    @Override
    public String newEntryId() {
      return "entry-" + ++entry;
    }
  }

  private static final class InMemoryStore implements SessionStore, SessionEntryStore {
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, SessionEntry> entries = new HashMap<>();

    @Override
    public Optional<Session> find(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void create(Session session) {
      if (sessions.putIfAbsent(session.sessionId(), session) != null) {
        throw new IllegalArgumentException("duplicate session");
      }
    }

    @Override
    public void createFork(Session session, List<SessionEntry> clone) {
      create(session);
      for (SessionEntry entry : clone) {
        entries.put(entry.entryId(), entry);
      }
    }

    @Override
    public void append(SessionEntry entry, String expectedLeafEntryId) {
      Session session = sessions.get(entry.sessionId());
      if (session == null || !Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
        throw new SessionLeafConflictException(entry.sessionId(), expectedLeafEntryId);
      }
      entries.put(entry.entryId(), entry);
      sessions.put(
          session.sessionId(),
          new Session(
              session.sessionId(),
              session.workspaceId(),
              session.parentSessionId(),
              entry.entryId(),
              session.createdAt()));
    }

    @Override
    public boolean compareAndSetLeaf(String sessionId, String expectedLeafEntryId, String newLeafEntryId) {
      Session session = sessions.get(sessionId);
      if (session == null || !Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
        return false;
      }
      sessions.put(
          sessionId,
          new Session(
              sessionId,
              session.workspaceId(),
              session.parentSessionId(),
              newLeafEntryId,
              session.createdAt()));
      return true;
    }

    @Override
    public Optional<SessionEntry> find(String sessionId, String entryId) {
      SessionEntry entry = entries.get(entryId);
      return entry != null && entry.sessionId().equals(sessionId) ? Optional.of(entry) : Optional.empty();
    }

    @Override
    public List<SessionEntry> loadPath(String sessionId, String leafEntryId) {
      List<SessionEntry> result = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String id = leafEntryId;
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

    void put(Session session, List<SessionEntry> path) {
      sessions.put(session.sessionId(), session);
      path.forEach(entry -> entries.put(entry.entryId(), entry));
    }
  }
}
