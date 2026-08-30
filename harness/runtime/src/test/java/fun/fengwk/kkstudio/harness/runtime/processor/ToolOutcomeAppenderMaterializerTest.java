package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.entry;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ToolOutcomeAppender 的 materializer 端口语义：只有 SUCCEEDED 且注入端口时才物化； FAILED/CANCELLED/UNKNOWN
 * 即使注入端口也走普通失败 payload 路径，绝不触碰 materializer。
 */
class ToolOutcomeAppenderMaterializerTest {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  @Test
  void nonSucceededStatusesNeverInvokeMaterializerAndUseOrdinaryPayloads() {
    for (ToolInvocationStatus status :
        List.of(
            ToolInvocationStatus.FAILED,
            ToolInvocationStatus.CANCELLED,
            ToolInvocationStatus.UNKNOWN)) {
      InMemoryHarnessStore store = new InMemoryHarnessStore();
      AtomicBoolean materialized = new AtomicBoolean(false);
      ToolResultHistoryMaterializer materializer =
          (sessionId, result) -> {
            materialized.set(true);
            return List.of(new TextMessageContent("must not appear"));
          };
      var chain =
          seedToolChain(store, List.of("call-1"), ModelInvocationStatus.SUCCEEDED, List.of(status));
      ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
      var before = path(store, chain.turn().threadId());
      ToolOutcomeAppender.Applied applied =
          store.transaction(
              tx ->
                  ToolOutcomeAppender.append(
                      tx,
                      before.root().sessionId(),
                      chain.assistantEntryId(),
                      invocation,
                      NOW,
                      materializer));

      assertFalse(materialized.get(), "materializer must never run for " + status);
      MessagePayload actual =
          assertInstanceOf(MessagePayload.class, entry(store, applied.headEntryId()).payload());
      assertEquals(PAYLOAD_MAPPER.toolResultPayload(invocation), actual);
    }
  }

  @Test
  void succeededStatusInvokesMaterializerAndPersistsMaterializedContents() {
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
        new ToolResult("call-1", List.of(new TextResultContent("raw")), false, "{}"));
    ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
    var before = path(store, chain.turn().threadId());

    List<AgentMessageContent> contents =
        List.of(new TextMessageContent("materialized"), new TextMessageContent("second"));
    ToolOutcomeAppender.Applied applied =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(
                    tx,
                    before.root().sessionId(),
                    chain.assistantEntryId(),
                    invocation,
                    NOW,
                    (sessionId, result) -> contents));

    MessagePayload actual =
        assertInstanceOf(MessagePayload.class, entry(store, applied.headEntryId()).payload());
    assertEquals(PAYLOAD_MAPPER.toolResultPayload(invocation, contents), actual);
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

  /** materializer 返回空内容时回退为单个空 TextMessageContent，head ToolResult 仍被持久化。 */
  @Test
  void materializerEmptyContentsFallBackToEmptyText() {
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
        new ToolResult("call-1", List.of(new TextResultContent("raw")), false, "{}"));
    ToolInvocation invocation = tool(store, chain.toolInvocationIds().getFirst());
    var before = path(store, chain.turn().threadId());

    ToolOutcomeAppender.Applied applied =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(
                    tx,
                    before.root().sessionId(),
                    chain.assistantEntryId(),
                    invocation,
                    NOW,
                    (sessionId, result) -> List.of()));

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
