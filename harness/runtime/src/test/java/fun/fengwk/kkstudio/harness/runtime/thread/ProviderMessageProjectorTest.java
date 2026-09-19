package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider 投影契约：native 资格按调用名在<b>当前 bindings</b> 中逐次判定；未命中者降级为 assistant 文本 / USER 内容，durable Entry
 * 与 callIndex 永不被改写；同一 assistant 之后 native TOOL 结果先于降级 USER 结果输出；被降级改写的 assistant 不再保留 replay
 * state。
 */
class ProviderMessageProjectorTest {

  private static final Set<String> ALL_NATIVE = Set.of("lookup");

  /** 全部调用名都在当前 bindings 中：保留 native tool call / TOOL result，且 replay state 保留。 */
  @Test
  void allNativeCallsKeepNativeStructureAndReplayState() {
    ProviderReplayState replayState = sampleReplayState();
    ProviderMessageProjector projector = new ProviderMessageProjector(ALL_NATIVE);

    List<ProviderMessage> projected =
        projector.projectSources(
            List.of(
                ProviderMessageProjector.ProjectedMessage.of(userMessage("question"), null),
                ProviderMessageProjector.ProjectedMessage.of(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new TextMessageContent("answer"),
                            new ThinkingMessageContent("reasoning"),
                            new JsonMessageContent("{\"answer\":true}"),
                            new ToolCallMessageContent(
                                "call-1", "lookup", "lookup", "{\"key\":\"value\"}"))),
                    replayState),
                ProviderMessageProjector.ProjectedMessage.of(
                    new AgentMessage(
                        AgentMessageRole.TOOL,
                        List.of(
                            new ToolResultMessageContent(
                                "call-1",
                                "lookup",
                                "lookup",
                                List.of(
                                    new TextMessageContent("tool output"),
                                    new JsonMessageContent("{\"found\":true}")),
                                true,
                                "{\"code\":\"NOT_FOUND\"}"))),
                    null)));

    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(List.of(new ProviderTextBlock("question")), projected.get(0).contents());
    assertEquals(
        List.of(
            new ProviderTextBlock("answer"),
            new ProviderThinkingBlock("reasoning"),
            new ProviderJsonBlock("{\"answer\":true}"),
            new ProviderToolCallBlock(
                new ProviderToolCall("call-1", "lookup", "{\"key\":\"value\"}"))),
        projected.get(1).contents());
    // 未发生降级改写：replay state 原样保留。
    assertEquals(replayState, projected.get(1).replayState());
    assertEquals(
        List.of(
            new ProviderToolResultBlock(
                "call-1",
                "lookup",
                List.of(
                    new ProviderTextBlock("tool output"),
                    new ProviderJsonBlock("{\"found\":true}")),
                true,
                "{\"code\":\"NOT_FOUND\"}")),
        projected.get(2).contents());
  }

  /** 全部调用名都不在当前 bindings 中：assistant tool call 与 TOOL 结果一并降级，绝不产生 TOOL 消息。 */
  @Test
  void allDowngradedCallsNeverProduceToolMessages() {
    ProviderReplayState replayState = sampleReplayState();
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of("bash"));

    List<ProviderMessage> projected =
        projector.projectSources(
            List.of(
                ProviderMessageProjector.ProjectedMessage.of(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new TextMessageContent("answer"),
                            new ToolCallMessageContent(
                                "call-1", "lookup", "lookup", "{\"key\":\"value\"}"))),
                    replayState),
                ProviderMessageProjector.ProjectedMessage.of(
                    new AgentMessage(
                        AgentMessageRole.TOOL,
                        List.of(
                            new ToolResultMessageContent(
                                "call-1",
                                "lookup",
                                "lookup",
                                List.of(new TextMessageContent("tool output")),
                                false,
                                "{}"))),
                    null)));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    ProviderMessage assistant = projected.get(0);
    assertEquals(2, assistant.contents().size());
    assertEquals(new ProviderTextBlock("answer"), assistant.contents().get(0));
    // 降级文本逐字携带调用身份与 arguments。
    String downgradedCall = ((ProviderTextBlock) assistant.contents().get(1)).text();
    assertTrue(downgradedCall.contains("`lookup`"), downgradedCall);
    assertTrue(downgradedCall.contains("`call-1`"), downgradedCall);
    assertTrue(downgradedCall.contains("```\n{\"key\":\"value\"}\n```"), downgradedCall);
    // 被降级改写的 assistant 不再携带 replay state（native payload 已与投影内容不一致）。
    assertNull(assistant.replayState());
    String resultText = ((ProviderTextBlock) projected.get(1).contents().get(0)).text();
    assertTrue(resultText.startsWith("Tool result for `lookup` (call id `call-1`):"), resultText);
    assertTrue(resultText.contains("```\ntool output\n```"), resultText);
  }

  /**
   * 混合 batch：同一 assistant 之后的 native 结果必须先于降级 USER 结果输出，以满足 provider 的 tool-call
   * adjacency；组内相对顺序保持不变。
   */
  @Test
  void mixedBatchEmitsNativeToolResultsBeforeDowngradedUserResults() {
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-native", "lookup", "lookup", "{}"),
                        new ToolCallMessageContent("call-legacy", "bash", "bash", "{}"))),
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-legacy",
                            "bash",
                            "bash",
                            List.of(new TextMessageContent("legacy output")),
                            false,
                            "{}"))),
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-native",
                            "lookup",
                            "lookup",
                            List.of(new TextMessageContent("native output")),
                            false,
                            "{}")))));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    // 先 native TOOL 结果（即便它排在降级结果之后到达），并保持其调用身份。
    ProviderToolResultBlock nativeResult =
        (ProviderToolResultBlock) projected.get(1).contents().get(0);
    assertEquals("call-native", nativeResult.toolCallId());
    assertEquals("native output", textOf(nativeResult.contents().get(0)));
    // 再降级 USER 结果。
    String downgradedText = ((ProviderTextBlock) projected.get(2).contents().get(0)).text();
    assertTrue(downgradedText.contains("`call-legacy`"), downgradedText);
    assertTrue(downgradedText.contains("```\nlegacy output\n```"), downgradedText);
  }

  /** 同一 assistant 后的多条 native 结果必须保持投影相对顺序。 */
  @Test
  void nativeResultsKeepRelativeOrder() {
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "lookup", "lookup", "{}"),
                        new ToolCallMessageContent("call-2", "lookup", "lookup", "{}"))),
                toolResult("call-1", "lookup", "first"),
                toolResult("call-2", "lookup", "second")));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(
        "call-1", ((ProviderToolResultBlock) projected.get(1).contents().get(0)).toolCallId());
    assertEquals(
        "call-2", ((ProviderToolResultBlock) projected.get(2).contents().get(0)).toolCallId());
    assertEquals(
        "first",
        textOf(((ProviderToolResultBlock) projected.get(1).contents().get(0)).contents().get(0)));
    assertEquals(
        "second",
        textOf(((ProviderToolResultBlock) projected.get(2).contents().get(0)).contents().get(0)));
  }

  /** 动态围栏规则：反引号数取 {@code max(3, 最长连续段 + 1)}，不带语言标识。 */
  @Test
  void dynamicFenceWidensPastLongestBacktickRun() {
    assertEquals("```\nplain\n```", ProviderMessageProjector.fence("plain"));
    assertEquals("```\na`b``c\n```", ProviderMessageProjector.fence("a`b``c"));
    // 内容含 3 个反引号组成的最长连续段 => 围栏必须是 4 个反引号。
    assertEquals("````\n```\n````", ProviderMessageProjector.fence("```"));
    assertEquals("`````\n````\n`````", ProviderMessageProjector.fence("````"));
  }

  /** 围栏内逐字保留 payload 原始空白，不做 strip / 折叠。 */
  @Test
  void downgradedPayloadsPreserveWhitespaceVerbatim() {
    String payload = "  keep leading and trailing  \n```\ninner fence\n```\n  ";
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                assistantToolCall("call-1", "lookup", "{\"q\":\"x\"}"),
                toolResult("call-1", "lookup", payload)));

    String downgradedText = ((ProviderTextBlock) projected.get(1).contents().get(0)).text();
    assertTrue(downgradedText.contains("````\n" + payload + "\n````"), downgradedText);
    assertTrue(downgradedText.contains(payload), downgradedText);
  }

  /** 无法表示为文本的 durable resource 块保留为显式说明后的 USER 块，而不是丢弃内容。 */
  @Test
  void durableResourcesInDowngradedResultsStayExplicitUserBlocks() {
    UUID blobId = new UUID(0L, 7L);
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                assistantToolCall("call-1", "lookup", "{}"),
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "lookup",
                            "lookup",
                            List.of(
                                new TextMessageContent("attached"),
                                ResourceMessageContent.media(blobId, "scan.png", "tiny preview")),
                            false,
                            "{}")))));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    List<ProviderContentBlock> blocks = projected.get(1).contents();
    assertEquals(2, blocks.size());
    assertTrue(((ProviderTextBlock) blocks.get(0)).text().contains("```\nattached\n```"));
    assertEquals(ProviderResourceBlock.media(blobId, "scan.png", "tiny preview"), blocks.get(1));
  }

  /** 未被调用名覆盖的调用只降级为文本，不合成结果；native 未配对调用才合成 error ToolResult。 */
  @Test
  void onlyUnpairedNativeCallsGetSyntheticOrphanResults() {
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-native", "lookup", "lookup", "{}"),
                        new ToolCallMessageContent("call-legacy", "bash", "bash", "{}")))));

    // assistant + 仅 native 调用的合成 error ToolResult；降级调用不合成结果。
    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    ProviderToolResultBlock synthetic =
        (ProviderToolResultBlock) projected.get(1).contents().get(0);
    assertEquals("call-native", synthetic.toolCallId());
    assertEquals("lookup", synthetic.toolName());
    assertTrue(synthetic.error());
    assertEquals("No result provided", textOf(synthetic.contents().get(0)));
  }

  /** 同一 toolCallId 在后续再次出现时，各自独立修复 orphan，不跨出现位置共享状态。 */
  @Test
  void synthesizesErrorForEachSubsequentOrphanWithTheSameToolCallId() {
    ProviderMessageProjector projector = new ProviderMessageProjector(ALL_NATIVE);

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                assistantToolCall("call-1"),
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("steer"))),
                assistantToolCall("call-1")));

    assertEquals(
        List.of(
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL,
            ProviderMessageRole.USER,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertSyntheticOrphanResult(projected.get(1));
    assertSyntheticOrphanResult(projected.get(4));
  }

  /** 纯 durable resource 块（不经降级路径）投影为 durable-safe 的 ProviderResourceBlock。 */
  @Test
  void projectsDurableResourceBlocksPreservingBlobFacts() {
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        ResourceMessageContent.media(new UUID(0L, 1L), "a.txt"),
                        ResourceMessageContent.media(new UUID(0L, 2L), "b.txt", "preview")))));

    assertEquals(
        List.of(
            ProviderResourceBlock.media(new UUID(0L, 1L), "a.txt", ""),
            ProviderResourceBlock.media(new UUID(0L, 2L), "b.txt", "preview")),
        projected.get(0).contents());
  }

  @Test
  void projectExternalizedTextResourceCarriesStructureFacts() {
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of());
    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        ResourceMessageContent.externalizedText(
                            new UUID(0L, 1L), "res.txt", 100L, 10L, "preview")))));

    assertEquals(
        List.of(
            ProviderResourceBlock.externalizedText(
                new UUID(0L, 1L), "res.txt", 100L, 10L, "preview")),
        projected.get(0).contents());
  }

  /** 投影是纯函数：不修改 durable AgentMessage / contents，也不改写 tool call 身份与顺序。 */
  @Test
  void projectionNeverMutatesDurableEntries() {
    List<AgentMessageContent> contents =
        List.of(
            new ToolCallMessageContent("call-1", "bash", "bash", "{}"),
            new TextMessageContent("answer"));
    AgentMessage assistant = new AgentMessage(AgentMessageRole.ASSISTANT, contents);
    AgentMessage result =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1", "bash", "bash", List.of(new TextMessageContent("ok")), false, "{}")));
    List<AgentMessage> durable = List.of(assistant, result);
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of("lookup"));

    projector.project(durable);

    // durable 消息与 contents 逐字不变，且 tool call 身份 / 顺序未被改写。
    assertEquals(List.of(assistant, result), durable);
    assertEquals(
        new ToolCallMessageContent("call-1", "bash", "bash", "{}"), assistant.contents().get(0));
    assertEquals(2, assistant.contents().size());
    assertFalse(assistant.contents().isEmpty());
  }

  private static AgentMessage assistantToolCall(String toolCallId) {
    return assistantToolCall(toolCallId, "lookup", "{}");
  }

  private static AgentMessage assistantToolCall(
      String toolCallId, String toolName, String argumentsJson) {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT,
        List.of(new ToolCallMessageContent(toolCallId, toolName, toolName, argumentsJson)));
  }

  private static AgentMessage toolResult(String toolCallId, String toolName, String text) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                toolCallId,
                toolName,
                toolName,
                List.of(new TextMessageContent(text)),
                false,
                "{}")));
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static String textOf(Object block) {
    return ((ProviderTextBlock) block).text();
  }

  private static void assertSyntheticOrphanResult(ProviderMessage message) {
    assertEquals(1, message.contents().size());
    assertTrue(message.contents().get(0) instanceof ProviderToolResultBlock);
    ProviderToolResultBlock result = (ProviderToolResultBlock) message.contents().get(0);
    assertEquals("call-1", result.toolCallId());
    assertEquals("lookup", result.toolName());
    assertTrue(result.error());
    assertEquals("{}", result.detailsJson());
    assertEquals("No result provided", textOf(result.contents().get(0)));
  }

  private static ProviderReplayState sampleReplayState() {
    return new ProviderReplayState(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        new ProviderReplayAffinity(
            ProviderType.ANTHROPIC, "anthropic", UUID.randomUUID(), "claude-3-5-sonnet"),
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        JsonNodeFactory.instance.objectNode().put("k", "v"));
  }
}
