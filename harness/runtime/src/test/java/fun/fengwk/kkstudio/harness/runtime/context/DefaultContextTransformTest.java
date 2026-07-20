package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;

/** 消息路径投影只处理 compaction；不再 fold 运行时配置。 */
class DefaultContextTransformTest {
  private final DefaultContextTransform transform = new DefaultContextTransform();

  @Test
  void keepsFullPathWithoutEffectiveCompaction() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, new RootEntryPayload()),
            entry(2L, 1L, userMessage("hello")),
            entry(3L, 2L, userMessage("world")));
    List<SessionEntry> result = transform.transform(path);
    assertEquals(List.of(1L, 2L, 3L), result.stream().map(SessionEntry::id).toList());
  }

  @Test
  void retainsCompactionAndKeptAncestorSubtree() {
    List<SessionEntry> path =
        List.of(
            entry(1L, null, new RootEntryPayload()),
            entry(2L, 1L, userMessage("old")),
            entry(3L, 2L, userMessage("kept")),
            entry(4L, 3L, new CompactionEntryPayload("summary", 3L, 10, "{}")),
            entry(5L, 4L, userMessage("after")));
    List<SessionEntry> result = transform.transform(path);
    assertEquals(List.of(4L, 3L, 5L), result.stream().map(SessionEntry::id).toList());
    assertInstanceOf(CompactionEntryPayload.class, result.get(0).payload());
  }

  private static MessageEntryPayload userMessage(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  private static SessionEntry entry(long id, Long parent, SessionEntryPayload payload) {
    return new SessionEntry(
        id, 1L, parent, payload.type(), payload, Instant.parse("2026-01-01T00:00:00Z"));
  }
}
