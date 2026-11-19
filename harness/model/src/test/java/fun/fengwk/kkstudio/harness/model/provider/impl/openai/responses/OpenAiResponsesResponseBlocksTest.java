package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;

/** Direct boundary tests for terminal Responses items after stream-state validation. */
class OpenAiResponsesResponseBlocksTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ProviderDescriptor DESCRIPTOR =
      new ProviderDescriptor(
          "responses-test",
          ProviderType.OPENAI_RESPONSES,
          "https://api.openai.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)));

  @Test
  void mapsTerminalItemsToDurableBlocksAndPreservesUnknownShapes() throws Exception {
    ArrayNode output =
        (ArrayNode)
            MAPPER.readTree(
                """
                [
                  {"type":"message","id":"empty","role":"assistant","content":[]},
                  {"type":"message","id":"msg","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"answer","annotations":[]},
                    {"type":"refusal","refusal":"denied","future":"kept"},
                    {"type":"future_part","future_value":true}
                  ]},
                  {"type":"reasoning","id":"reason","status":"completed","encrypted_content":"cipher","summary":[
                    {"type":"summary_text","text":"thought one","annotations":[]},
                    {"type":"summary_text","text":"thought two"}
                  ]},
                  {"type":"reasoning","id":"opaque_reason","status":"completed","summary":[
                    {"type":"future_summary","value":1}
                  ]},
                  {"type":"reasoning","id":"empty_reason","summary":[]},
                  {"type":"function_call","id":"fc1","call_id":"call1","name":"calc","arguments":"{}","status":"completed"},
                  {"type":"function_call","id":"fc2","call_id":"call-missing","name":"calc","arguments":"{}"},
                  {"type":"web_search_call","id":"search1","action":{"type":"search","query":"weather"}}
                ]
                """);
    ProviderToolCall call = new ProviderToolCall("call1", "calc", "{}");

    List<ProviderContentBlock> blocks =
        new OpenAiResponsesResponseBlocks(DESCRIPTOR, "gpt-5.4-mini").build(output, List.of(call));

    assertEquals(11, blocks.size());
    ProviderOpaqueBlock emptyMessage = (ProviderOpaqueBlock) blocks.get(0);
    assertEquals("message", emptyMessage.providerBlockData().itemType());
    assertEquals("empty", emptyMessage.providerBlockData().itemId());
    assertTrue(emptyMessage.providerBlockData().itemStart());
    assertEquals(
        "assistant",
        emptyMessage.providerBlockData().nativeFields().path("item").path("role").asString());
    assertTrue(
        emptyMessage.providerBlockData().nativeFields().path("item").path("content").isEmpty());

    ProviderTextBlock text = (ProviderTextBlock) blocks.get(1);
    assertEquals("answer", text.text());
    assertTrue(text.providerBlockData().itemStart());
    assertEquals("msg", text.providerBlockData().itemId());
    ProviderTextBlock refusal = (ProviderTextBlock) blocks.get(2);
    assertEquals("denied", refusal.text());
    assertTrue(refusal.providerBlockData().refusal());
    assertEquals("refusal", refusal.providerBlockData().contentType());
    assertEquals(
        "kept",
        refusal.providerBlockData().nativeFields().path("contentFields").path("future").asString());
    ProviderOpaqueBlock unknownPart = (ProviderOpaqueBlock) blocks.get(3);
    assertEquals("message_content", unknownPart.blockType());
    assertEquals("future_part", unknownPart.providerBlockData().contentType());
    assertTrue(
        unknownPart
            .providerBlockData()
            .nativeFields()
            .path("contentFields")
            .path("future_value")
            .asBoolean());
    assertFalse(unknownPart.providerBlockData().itemStart());

    ProviderThinkingBlock firstThinking = (ProviderThinkingBlock) blocks.get(4);
    ProviderThinkingBlock secondThinking = (ProviderThinkingBlock) blocks.get(5);
    assertEquals("thought one", firstThinking.thinking());
    assertEquals("reason", firstThinking.providerBlockData().itemId());
    assertEquals("cipher", firstThinking.providerBlockData().encryptedContent());
    assertTrue(firstThinking.providerBlockData().itemStart());
    assertEquals("thought two", secondThinking.thinking());
    assertFalse(secondThinking.providerBlockData().itemStart());

    ProviderOpaqueBlock opaqueReasoning = (ProviderOpaqueBlock) blocks.get(6);
    assertEquals("reasoning", opaqueReasoning.blockType());
    assertEquals("opaque_reason", opaqueReasoning.providerBlockData().itemId());
    assertEquals(
        "future_summary",
        opaqueReasoning
            .providerBlockData()
            .nativeFields()
            .path("item")
            .path("summary")
            .get(0)
            .path("type")
            .asString());
    ProviderOpaqueBlock emptyReasoning = (ProviderOpaqueBlock) blocks.get(7);
    assertEquals("reasoning", emptyReasoning.blockType());

    ProviderToolCallBlock knownCall = (ProviderToolCallBlock) blocks.get(8);
    assertEquals(call, knownCall.toolCall());
    assertEquals("fc1", knownCall.providerBlockData().itemId());
    assertEquals("function_call", knownCall.providerBlockData().itemType());
    ProviderOpaqueBlock unpairedCall = (ProviderOpaqueBlock) blocks.get(9);
    assertEquals("function_call", unpairedCall.blockType());
    assertEquals("fc2", unpairedCall.providerBlockData().itemId());
    assertEquals(
        "call-missing",
        unpairedCall.providerBlockData().nativeFields().path("item").path("call_id").asString());
    assertEquals(
        ProviderReplayFormat.OPENAI_RESPONSES, unpairedCall.providerBlockData().protocol());
    ProviderOpaqueBlock hostedItem = (ProviderOpaqueBlock) blocks.get(10);
    assertEquals("web_search_call", hostedItem.providerBlockData().itemType());
    assertEquals("search1", hostedItem.providerBlockData().itemId());
  }
}
