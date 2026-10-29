package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.entry;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.UUID;

/** ToolOutcomeAppender 只把已稳定的 terminal 结果映射成 history Entry，不再消费 upload 或二次物化。 */
class ToolOutcomeAppenderTest {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  @Test
  void nonSucceededStatusesUseOrdinaryErrorPayloads() {
    for (ToolInvocationStatus status :
        List.of(
            ToolInvocationStatus.FAILED,
            ToolInvocationStatus.CANCELLED,
            ToolInvocationStatus.UNKNOWN)) {
      InMemoryHarnessStore store = new InMemoryHarnessStore();
      var chain =
          seedToolChain(store, List.of("call-1"), ModelInvocationStatus.SUCCEEDED, List.of(status));
      ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
      var before = path(store, chain.turn().threadId());
      ToolOutcomeAppender.Applied applied =
          store.transaction(
              tx ->
                  ToolOutcomeAppender.append(
                      tx, before.root().sessionId(), chain.assistantEntryId(), invocation, NOW));

      MessagePayload actual =
          assertInstanceOf(MessagePayload.class, entry(store, applied.headEntryId()).payload());
      assertEquals(PAYLOAD_MAPPER.toolResultPayload(invocation), actual);
    }
  }

  @Test
  void succeededStatusPersistsStableSessionResource() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    UUID blobId = new UUID(0L, 1L);
    ResourceRef resource =
        new ResourceRef(
            ResourceRef.sessionResourceUri(blobId),
            "text/plain",
            "report.txt",
            3L,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    succeedToolWith(
        store,
        chain.toolInvocationIds().getFirst(),
        new ToolResult(
            "call-1",
            List.of(new ResourceResultContent(resource, "complete preview")),
            false,
            "{}"));
    ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
    var before = path(store, chain.turn().threadId());

    ToolOutcomeAppender.Applied applied =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(
                    tx, before.root().sessionId(), chain.assistantEntryId(), invocation, NOW));

    ToolResultMessageContent result =
        assertInstanceOf(
            ToolResultMessageContent.class,
            ((MessagePayload) entry(store, applied.headEntryId()).payload())
                .message()
                .contents()
                .getFirst());
    ResourceMessageContent mapped =
        assertInstanceOf(ResourceMessageContent.class, result.contents().getFirst());
    assertEquals(blobId, mapped.blobId());
    assertEquals("report.txt", mapped.name());
    assertEquals("complete preview", mapped.preview());
  }

  /** append 只接受 terminal Tool：READY 行被拒绝（不进入任何 Entry 追加）。 */
  @Test
  void rejectsNonTerminalToolAppend() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
    var before = path(store, chain.turn().threadId());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    ToolOutcomeAppender.append(
                        tx, before.root().sessionId(), chain.assistantEntryId(), invocation, NOW)));
  }

  /** SUCCEEDED 的 CUSTOM effects 按冻结顺序位于 ToolResult 之前；head 仍是 ToolResult Entry。 */
  @Test
  void appendsCustomEffectsBeforeToolResultInOrder() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    UUID toolId = chain.toolInvocationIds().getFirst();
    succeedToolWith(
        store,
        toolId,
        new ToolResult("call-1", List.of(new TextResultContent("ok")), false, "{}"),
        new ToolEffectBatch(
            List.of(
                new CustomEntryPayload("goal", "type-a", 1, "{\"a\":1}"),
                new CustomEntryPayload("goal", "type-b", 2, "{\"b\":2}"))));
    ToolInvocation invocation = tool(store, toolId);
    var before = path(store, chain.turn().threadId());

    ToolOutcomeAppender.Applied applied =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(
                    tx, before.root().sessionId(), chain.assistantEntryId(), invocation, NOW));

    // head 是 ToolResult Entry；其父链依次是 effect-b、effect-a、assistant。
    Entry result = entry(store, applied.headEntryId());
    assertInstanceOf(MessagePayload.class, result.payload());
    Entry effectB = entry(store, result.parentEntryId());
    assertInstanceOf(CustomEntryPayload.class, effectB.payload());
    assertEquals("type-b", ((CustomEntryPayload) effectB.payload()).customType());
    Entry effectA = entry(store, effectB.parentEntryId());
    assertInstanceOf(CustomEntryPayload.class, effectA.payload());
    assertEquals("type-a", ((CustomEntryPayload) effectA.payload()).customType());
    assertEquals(chain.assistantEntryId(), effectA.parentEntryId());
  }

  /** 空结果稳定落为空 TextMessageContent，head ToolResult 仍被持久化。 */
  @Test
  void emptyResultFallsBackToEmptyText() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    succeedToolWith(
        store,
        chain.toolInvocationIds().getFirst(),
        new ToolResult("call-1", List.of(), false, "{}"));
    ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
    var before = path(store, chain.turn().threadId());

    ToolOutcomeAppender.Applied applied =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(
                    tx, before.root().sessionId(), chain.assistantEntryId(), invocation, NOW));

    ToolResultMessageContent result =
        assertInstanceOf(
            ToolResultMessageContent.class,
            ((MessagePayload) entry(store, applied.headEntryId()).payload())
                .message()
                .contents()
                .getFirst());
    assertEquals(List.of(new TextMessageContent("")), result.contents());
  }
}
