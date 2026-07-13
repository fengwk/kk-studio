package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolsetChangeEntryPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultContextTransformTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final long SESSION_ID = 1L;
  private final DefaultContextTransform transform = new DefaultContextTransform();

  /** 配置变更必须建立在 snapshot 之上，且没有 snapshot 的路径不能投影。 */
  @Test
  void shouldRequireSnapshotBeforeConfigurationChanges() {
    ContextProjectionException noSnapshot =
        assertThrows(
            ContextProjectionException.class,
            () -> transform.transform(List.of(entry(1L, null, message("orphan")))));
    ContextProjectionException modelBeforeSnapshot =
        assertThrows(
            ContextProjectionException.class,
            () ->
                transform.transform(
                    List.of(entry(1L, null, new ModelChangeEntryPayload("m2", "fast")))));
    ContextProjectionException toolsBeforeSnapshot =
        assertThrows(
            ContextProjectionException.class,
            () ->
                transform.transform(
                    List.of(entry(1L, null, new ToolsetChangeEntryPayload(List.of("read"))))));

    assertEquals("active path has no agent snapshot", noSnapshot.getMessage());
    assertEquals(
        "model change requires a preceding agent snapshot", modelBeforeSnapshot.getMessage());
    assertEquals(
        "toolset change requires a preceding agent snapshot", toolsBeforeSnapshot.getMessage());
    assertEquals(
        "path",
        assertThrows(NullPointerException.class, () -> transform.transform(null)).getMessage());
  }

  /** 后续 snapshot 会重置旧配置，其后的 model/toolset change 再按路径顺序生效。 */
  @Test
  void shouldResolveLatestSnapshotAndFollowingChanges() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, snapshot("old prompt", "old-model", List.of("old-tool"))),
            entry(2L, 1L, new ModelChangeEntryPayload("discarded-model", "slow")),
            entry(3L, 2L, snapshot("new prompt", "base-model", List.of("base-tool"))),
            entry(4L, 3L, new ToolsetChangeEntryPayload(List.of("read", "write"))),
            entry(5L, 4L, new ModelChangeEntryPayload("final-model", "fast")));

    ContextState state = transform.transform(path);

    assertEquals("new prompt", state.config().systemPrompt());
    assertEquals("final-model", state.config().modelId());
    assertEquals("fast", state.config().variant());
    assertEquals(List.of("read", "write"), state.config().tools());
    assertEquals(path, state.entries());
  }

  /** 多次压缩只采用最后一个有效边界，并排除保留区间中的无效 compaction。 */
  @Test
  void shouldUseLatestValidCompactionAndExcludeInvalidOnes() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, snapshot("system", "model", List.of())),
            entry(2L, 1L, message("discarded")),
            entry(3L, 2L, message("old-kept")),
            entry(4L, 3L, new CompactionEntryPayload("old", 3L, 10, "{}")),
            entry(5L, 4L, message("latest-kept")),
            entry(6L, 5L, new CompactionEntryPayload("invalid", 99L, 20, "{}")),
            entry(7L, 6L, message("after-invalid")),
            entry(8L, 7L, new CompactionEntryPayload("latest", 5L, 30, "{}")),
            entry(9L, 8L, message("after-latest")));

    ContextState state = transform.transform(path);

    assertEquals(List.of(8L, 5L, 7L, 9L), state.entries().stream().map(SessionEntry::id).toList());
    assertEquals("latest", ((CompactionEntryPayload) state.entries().get(0).payload()).summary());
  }

  /** 没有有效边界时保留普通 Entry，但所有非法 compaction 都不得进入模型路径。 */
  @Test
  void shouldRemoveInvalidCompactionsWithoutValidFallback() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, snapshot("system", "model", List.of())),
            entry(2L, 1L, new CompactionEntryPayload("future", 4L, 10, "{}")),
            entry(3L, 2L, message("kept")),
            entry(4L, 3L, new CompactionEntryPayload("self", 4L, 20, "{}")));

    ContextState state = transform.transform(path);

    assertEquals(List.of(1L, 3L), state.entries().stream().map(SessionEntry::id).toList());
  }

  private static AgentSnapshotEntryPayload snapshot(
      String prompt, String modelId, List<String> tools) {
    return new AgentSnapshotEntryPayload(
        new AgentSnapshot(prompt, modelId, "default", tools, List.of(), List.of(), "{}"));
  }

  private static MessageEntryPayload message(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  private static SessionEntry entry(long id, Long parentId, SessionEntryPayload payload) {
    return new SessionEntry(id, SESSION_ID, parentId, null, payload.type(), payload, NOW);
  }
}
