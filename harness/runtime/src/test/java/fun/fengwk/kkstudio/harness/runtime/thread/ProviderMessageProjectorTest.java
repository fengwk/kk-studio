package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector.NativeTool;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider 投影契约：native 资格按「调用名在当前 bindings + 冻结 Environment 名与当前同名工具一致」逐次判定；未命中者从 ASSISTANT wire
 * 移除，与其结果配对后合并为单条 USER 自然语言上下文（含 Tool 冻结的 action、中性回退、orphan 文本）。durable Entry 与 callIndex 永不被改写；
 * 同一 assistant 之后 native TOOL 结果先于该 USER 上下文输出；携带 opaque replay 的 assistant 不允许降级改写。
 */
class ProviderMessageProjectorTest {

  private static final String CONTEXT_HEADER = "Previous context:";

  /** 全部调用名都在当前 bindings 中：保留 native tool call / TOOL result，且 replay state 保留。 */
  @Test
  void allNativeCallsKeepNativeStructureAndReplayState() {
    ProviderReplayState replayState = sampleReplayState();
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

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
                            toolCall(
                                "call-1", "lookup", "lookup", "{\"key\":\"value\"}", null, null))),
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

  /** 冻结 Environment 与当前同名工具一致：即使调用携带 environmentName 也保持 native 结构。 */
  @Test
  void environmentBoundCallStaysNativeWhenEnvironmentMatches() {
    ProviderMessageProjector projector =
        new ProviderMessageProjector(List.of(new NativeTool("fs_read", "dev")));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "fs_read", "{\"path\":\"a.txt\"}", null, "dev"),
                toolResult("call-1", "fs_read", "file body")));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(
        new ProviderToolCallBlock(
            new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")),
        projected.get(0).contents().get(0));
    assertEquals("call-1", toolResultOf(projected.get(1)).toolCallId());
  }

  /**
   * Environment 切换：冻结时绑定的 Environment 名与当前同名工具不同，调用必须降级——既从 ASSISTANT wire 移除，也不产生 TOOL 结果，只在 USER
   * 上下文中以自然语言出现（这里使用了冻结 action）。
   */
  @Test
  void environmentSwitchDowngradesCallsBoundToPreviousEnvironment() {
    ProviderMessageProjector projector =
        new ProviderMessageProjector(List.of(new NativeTool("fs_read", "prod")));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "fs_read", "{\"path\":\"a.txt\"}", "read a.txt", "dev"),
                toolResult("call-1", "fs_read", "file body")));

    assertEquals(
        List.of(ProviderMessageRole.USER), projected.stream().map(ProviderMessage::role).toList());
    assertEquals(CONTEXT_HEADER + "\nread a.txt:\n```\nfile body\n```", textOf(projected.get(0)));
  }

  /** 当前 bindings 中不存在的工具名（Tool 已被移除）：降级为单条 USER 上下文。 */
  @Test
  void unbindableToolNamesDowngradeIntoUserContext() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("bash"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "lookup", "{\"key\":\"value\"}", "look up key", null),
                toolResult("call-1", "lookup", "tool output")));

    assertEquals(
        List.of(ProviderMessageRole.USER), projected.stream().map(ProviderMessage::role).toList());
    assertEquals(
        CONTEXT_HEADER + "\nlook up key:\n```\ntool output\n```", textOf(projected.get(0)));
  }

  /** 降级调用携带 Tool 冻结的 action：成功项为 {@code <action>:} + 结果；失败项为 {@code <action> failed:} + 结果。 */
  @Test
  void frozenActionsRenderSuccessAndFailureMarkers() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        toolCall(
                            "call-1", "lookup", "lookup", "{\"key\":\"a\"}", "read key a", null),
                        toolCall(
                            "call-2", "lookup", "lookup", "{\"key\":\"b\"}", "read key b", null))),
                toolResult("call-1", "lookup", "ok", false),
                toolResult("call-2", "lookup", "boom", true)));

    assertEquals(
        List.of(ProviderMessageRole.USER), projected.stream().map(ProviderMessage::role).toList());
    assertEquals(
        CONTEXT_HEADER + "\nread key a:\n```\nok\n```" + "\nread key b failed:\n```\nboom\n```",
        textOf(projected.get(0)));
  }

  /** 没有冻结 action 的调用（缺渲染器 / 渲染失败 / 定义已变化）使用确定性中性回退：不暴露 toolName / callId，逐字保留全部 arguments。 */
  @Test
  void callsWithoutFrozenActionUseNeutralVerbatimFallback() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "mcp__search", "{\"q\":\"x\",\"topK\":3}", null, null),
                toolResult("call-1", "mcp__search", "hits")));

    String text = textOf(projected.get(0));
    assertEquals(
        CONTEXT_HEADER
            + "\nexternal operation:\n```\n{\"q\":\"x\",\"topK\":3}\n```\n```\nhits\n```",
        text);
    // 回退绝不猜测语义：既不出现工具名，也不出现 call id，更不出现伪 Tool 协议。
    assertFalse(text.contains("mcp__search"), text);
    assertFalse(text.contains("call-1"), text);
    assertFalse(text.contains("Tool call"), text);
    assertFalse(text.contains("Tool result"), text);
  }

  /** 中性回退的失败调用同样以 {@code <action> failed:} 表达，且围栏内仍是逐字 arguments。 */
  @Test
  void fallbackCallWithFailedResultCarriesFailureMarker() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "mcp__search", "{\"q\":\"x\"}", null, null),
                toolResult("call-1", "mcp__search", "connection reset", true)));

    String text = textOf(projected.get(0));
    assertTrue(text.startsWith(CONTEXT_HEADER + "\nexternal operation failed:\n"), text);
    assertTrue(text.contains("```\nconnection reset\n```"), text);
  }

  /** 无结果配对的降级调用以 {@code No result provided} 表达，与 native orphan 的合成 TOOL 结果严格区分。 */
  @Test
  void orphanDowngradedCallsUseNoResultProvidedText() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                toolCallAssistant("call-1", "mcp__search", "{\"q\":\"x\"}", "search for x", null),
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("steer")))));

    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(CONTEXT_HEADER + "\nsearch for x:\nNo result provided", textOf(projected.get(0)));
    assertEquals(List.of(new ProviderTextBlock("steer")), projected.get(1).contents());
  }

  /** 降级结果按 toolCallId 与调用配对（而非按到达顺序），并保持 durable 调用顺序。 */
  @Test
  void downgradedResultsPairByCallIdAndKeepCallOrder() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        toolCall("call-1", "mcp__a", "mcp__a", "{\"a\":1}", "first action", null),
                        toolCall(
                            "call-2", "mcp__b", "mcp__b", "{\"b\":2}", "second action", null))),
                toolResult("call-2", "mcp__b", "second output", false),
                toolResult("call-1", "mcp__a", "first output", false)));

    assertEquals(
        List.of(ProviderMessageRole.USER), projected.stream().map(ProviderMessage::role).toList());
    assertEquals(
        CONTEXT_HEADER
            + "\nfirst action:\n```\nfirst output\n```"
            + "\nsecond action:\n```\nsecond output\n```",
        textOf(projected.get(0)));
  }

  /**
   * 混合 batch：同一 assistant 之后的 native TOOL 结果必须先于降级 USER 上下文输出，以满足 provider 的 tool-call adjacency；
   * 降级调用从 ASSISTANT wire 中移除，组内相对顺序保持不变。
   */
  @Test
  void mixedBatchEmitsNativeToolResultsBeforeDowngradedUserContext() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        toolCall("call-native", "lookup", "lookup", "{}", null, null),
                        toolCall("call-legacy", "bash", "bash", "{}", "run bash", null))),
                toolResult("call-legacy", "bash", "legacy output", false),
                toolResult("call-native", "lookup", "native output", false)));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    // ASSISTANT wire 只保留 native 调用块：降级调用不再以任何形式出现在 assistant 消息中。
    assertEquals(1, projected.get(0).contents().size());
    assertEquals(
        new ProviderToolCallBlock(new ProviderToolCall("call-native", "lookup", "{}")),
        projected.get(0).contents().get(0));
    // 先 native TOOL 结果（即便它排在降级结果之后到达），并保持其调用身份。
    ProviderToolResultBlock nativeResult = toolResultOf(projected.get(1));
    assertEquals("call-native", nativeResult.toolCallId());
    assertEquals("native output", textOf(nativeResult.contents().get(0)));
    // 再降级 USER 上下文。
    assertEquals(CONTEXT_HEADER + "\nrun bash:\n```\nlegacy output\n```", textOf(projected.get(2)));
  }

  /** 纯工具 assistant 即使投影后为空，也必须先拒绝不兼容的 opaque replay。 */
  @Test
  void assistantWithOnlyDowngradedCallsRejectsReplay() {
    ProviderReplayState replayState = sampleReplayState();
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    assertReplayRejected(
        projector,
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(toolCall("call-1", "bash", "bash", "{\"secret\":1}", null, null))),
        replayState);
  }

  /** 文本加降级调用不能通过只保留文本来丢弃 opaque replay。 */
  @Test
  void partiallyDowngradedAssistantRejectsReplay() {
    ProviderReplayState replayState = sampleReplayState();
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    assertReplayRejected(
        projector,
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("answer"),
                toolCall("call-1", "bash", "bash", "{\"secret\":1}", "run bash", null))),
        replayState);
  }

  /** 无 replay 的旧历史仍可保留 assistant 文本，并将失去绑定的调用降级为 USER 上下文。 */
  @Test
  void replayFreeAssistantKeepsTextWhenToolDowngrades() {
    List<ProviderMessage> projected =
        ProviderMessageProjector.byNames(Set.of("lookup"))
            .project(
                List.of(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(
                            new TextMessageContent("answer"),
                            toolCall("call-1", "bash", "bash", "{}", "run bash", null))),
                    toolResult("call-1", "bash", "output")));
    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals(List.of(new ProviderTextBlock("answer")), projected.get(0).contents());
    assertNull(projected.get(0).replayState());
    assertEquals(CONTEXT_HEADER + "\nrun bash:\n```\noutput\n```", textOf(projected.get(1)));
  }

  /** 混合 native/降级调用不能把 replay 错误地附着到部分保留的 assistant 内容上。 */
  @Test
  void mixedCallsRejectOpaqueReplayBeforeDowngrade() {
    assertReplayRejected(
        ProviderMessageProjector.byNames(Set.of("lookup")),
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                toolCall("call-native", "lookup", "lookup", "{}", null, null),
                toolCall("call-legacy", "bash", "bash", "{\"secret\":1}", null, null))),
        sampleReplayState());
  }

  /** 同名工具的冻结环境变化也必须拒绝 opaque replay，而非静默转为 USER 语义。 */
  @Test
  void environmentMismatchRejectsOpaqueReplay() {
    assertReplayRejected(
        new ProviderMessageProjector(List.of(new NativeTool("fs_read", "prod"))),
        toolCallAssistant("call-1", "fs_read", "{\"secret\":1}", null, "dev"),
        sampleReplayState());
  }

  /** 环境匹配时纯工具 assistant 的 replay 必须原样保留。 */
  @Test
  void environmentMatchPreservesOpaqueReplay() {
    ProviderReplayState replay = sampleReplayState();
    List<ProviderMessage> messages =
        new ProviderMessageProjector(List.of(new NativeTool("fs_read", "dev")))
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(
                        toolCallAssistant("call-1", "fs_read", "{}", null, "dev"), replay)));
    assertEquals(replay, messages.get(0).replayState());
  }

  /** 同一 assistant 后的多条 native 结果必须保持投影相对顺序。 */
  @Test
  void nativeResultsKeepRelativeOrder() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        toolCall("call-1", "lookup", "lookup", "{}", null, null),
                        toolCall("call-2", "lookup", "lookup", "{}", null, null))),
                toolResult("call-1", "lookup", "first"),
                toolResult("call-2", "lookup", "second")));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.TOOL),
        projected.stream().map(ProviderMessage::role).toList());
    assertEquals("call-1", toolResultOf(projected.get(1)).toolCallId());
    assertEquals("call-2", toolResultOf(projected.get(2)).toolCallId());
    assertEquals("first", textOf(toolResultOf(projected.get(1)).contents().get(0)));
    assertEquals("second", textOf(toolResultOf(projected.get(2)).contents().get(0)));
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

  /** 围栏内逐字保留 payload 原始空白，不做 strip / 折叠；动态围栏同样作用于 arguments 回退。 */
  @Test
  void downgradedPayloadsPreserveWhitespaceVerbatim() {
    String payload = "  keep leading and trailing  \n```\ninner fence\n```\n  ";
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                assistantToolCall("call-1", "lookup", "{\"q\":\"x\"}"),
                toolResult("call-1", "lookup", payload)));

    String text = textOf(projected.get(0));
    assertTrue(text.contains("````\n" + payload + "\n````"), text);
    assertTrue(text.contains("```\n{\"q\":\"x\"}\n```"), text);
  }

  /** 无法表示为文本的 durable resource 块保留为 USER 块（在文本之后、按序），而不是丢弃内容。 */
  @Test
  void durableResourcesInDowngradedResultsStayExplicitUserBlocks() {
    UUID blobId = new UUID(0L, 7L);
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

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
        List.of(ProviderMessageRole.USER), projected.stream().map(ProviderMessage::role).toList());
    List<ProviderContentBlock> blocks = projected.get(0).contents();
    assertEquals(2, blocks.size());
    assertTrue(textOf(blocks.get(0)).contains("```\nattached\n```"));
    assertEquals(ProviderResourceBlock.media(blobId, "scan.png", "tiny preview"), blocks.get(1));
  }

  /** 只有未被结果配对的 native 调用才合成 error ToolResult；降级调用只用 USER 文本表达 orphan。 */
  @Test
  void onlyUnpairedNativeCallsGetSyntheticOrphanResults() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        toolCall("call-native", "lookup", "lookup", "{}", null, null),
                        toolCall("call-legacy", "bash", "bash", "{}", "run bash", null)))));

    assertEquals(
        List.of(ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL, ProviderMessageRole.USER),
        projected.stream().map(ProviderMessage::role).toList());
    ProviderToolResultBlock synthetic = toolResultOf(projected.get(1));
    assertEquals("call-native", synthetic.toolCallId());
    assertEquals("lookup", synthetic.toolName());
    assertTrue(synthetic.error());
    assertEquals("No result provided", textOf(synthetic.contents().get(0)));
    assertEquals(CONTEXT_HEADER + "\nrun bash:\nNo result provided", textOf(projected.get(2)));
  }

  /** 同一 toolCallId 在后续再次出现时，各自独立修复 orphan，不跨出现位置共享状态。 */
  @Test
  void synthesizesErrorForEachSubsequentOrphanWithTheSameToolCallId() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

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
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

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

  /** 意图：冻结的图片输入档位是 durable 事实，投影必须原样带过（文本工件不带档位）。 */
  @Test
  void projectsFrozenImageInputTierIntoProviderBlocks() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());

    List<ProviderMessage> projected =
        projector.project(
            List.of(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        ResourceMessageContent.media(
                            new UUID(0L, 1L), "photo.png", "preview", ImageInputTier.P1080),
                        ResourceMessageContent.media(
                            new UUID(0L, 2L), "original.png", null, ImageInputTier.ORIGINAL),
                        ResourceMessageContent.media(new UUID(0L, 3L), "default.png")))));

    assertEquals(
        List.of(
            ProviderResourceBlock.media(
                new UUID(0L, 1L), "photo.png", "preview", ImageInputTier.P1080),
            ProviderResourceBlock.media(
                new UUID(0L, 2L), "original.png", "", ImageInputTier.ORIGINAL),
            ProviderResourceBlock.media(new UUID(0L, 3L), "default.png", "")),
        projected.get(0).contents());
    assertEquals(
        ImageInputTier.P1080,
        ((ProviderResourceBlock) projected.get(0).contents().get(0)).imageTier());
    assertNull(((ProviderResourceBlock) projected.get(0).contents().get(2)).imageTier());
  }

  @Test
  void projectExternalizedTextResourceCarriesStructureFacts() {
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());
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
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of("lookup"));

    projector.project(durable);

    // durable 消息与 contents 逐字不变，且 tool call 身份 / 顺序未被改写。
    assertEquals(List.of(assistant, result), durable);
    assertEquals(
        new ToolCallMessageContent("call-1", "bash", "bash", "{}"), assistant.contents().get(0));
    assertEquals(2, assistant.contents().size());
    assertFalse(assistant.contents().isEmpty());
  }

  /** NativeTool 拒绝 blank 工具名与 blank Environment 名：这两者是 durable 判定输入，不能是空串。 */
  @Test
  void nativeToolRejectsBlankIdentity() {
    assertThrows(IllegalArgumentException.class, () -> new NativeTool(" ", null));
    assertThrows(IllegalArgumentException.class, () -> new NativeTool("fs_read", " "));
  }

  private static AgentMessage assistantToolCall(String toolCallId) {
    return assistantToolCall(toolCallId, "lookup", "{}");
  }

  private static AgentMessage assistantToolCall(
      String toolCallId, String toolName, String argumentsJson) {
    return toolCallAssistant(toolCallId, toolName, argumentsJson, null, null);
  }

  private static AgentMessage toolCallAssistant(
      String toolCallId,
      String toolName,
      String argumentsJson,
      String historyAction,
      String environmentName) {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT,
        List.of(
            toolCall(
                toolCallId, toolName, toolName, argumentsJson, historyAction, environmentName)));
  }

  private static ToolCallMessageContent toolCall(
      String toolCallId,
      String toolName,
      String rendererKey,
      String argumentsJson,
      String historyAction,
      String environmentName) {
    return new ToolCallMessageContent(
        toolCallId, toolName, rendererKey, argumentsJson, historyAction, environmentName);
  }

  private static AgentMessage toolResult(String toolCallId, String toolName, String text) {
    return toolResult(toolCallId, toolName, text, false);
  }

  private static AgentMessage toolResult(
      String toolCallId, String toolName, String text, boolean error) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                toolCallId,
                toolName,
                toolName,
                List.of(new TextMessageContent(text)),
                error,
                "{}")));
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  /** USER 上下文项文本必然落在唯一文本块中；resource 块只会追加在其后。 */
  private static String textOf(ProviderMessage message) {
    return textOf(message.contents().get(0));
  }

  private static String textOf(Object block) {
    return ((ProviderTextBlock) block).text();
  }

  private static ProviderToolResultBlock toolResultOf(ProviderMessage message) {
    return (ProviderToolResultBlock) message.contents().get(0);
  }

  private static void assertSyntheticOrphanResult(ProviderMessage message) {
    assertEquals(1, message.contents().size());
    ProviderToolResultBlock result = toolResultOf(message);
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

  private static void assertReplayRejected(
      ProviderMessageProjector projector, AgentMessage assistant, ProviderReplayState replay) {
    ProviderException failure =
        assertThrows(
            ProviderException.class,
            () ->
                projector.projectSources(
                    List.of(ProviderMessageProjector.ProjectedMessage.of(assistant, replay))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failure.kind());
    assertTrue(failure.getMessage().contains("restore original tool bindings/environment"));
    assertTrue(failure.getMessage().contains("start a new context with an explicit summary"));
    assertFalse(failure.getMessage().contains("bash"));
    assertFalse(failure.getMessage().contains("fs_read"));
    assertFalse(failure.getMessage().contains("secret"));
    assertFalse(failure.getMessage().contains("call-"));
  }
}
