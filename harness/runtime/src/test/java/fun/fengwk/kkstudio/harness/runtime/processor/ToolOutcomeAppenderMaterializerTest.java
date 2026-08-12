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

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
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
        new ToolResult("call-1", List.of(new TextToolContent("raw")), false, "{}", false));
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
}
