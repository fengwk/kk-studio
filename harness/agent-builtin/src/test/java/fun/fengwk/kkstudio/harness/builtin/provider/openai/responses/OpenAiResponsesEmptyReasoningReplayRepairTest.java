package fun.fengwk.kkstudio.harness.builtin.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesRequestEncoder;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesStreamAccumulator;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Responses reasoning metadata is persisted on the content block that owns each native item. */
class OpenAiResponsesEmptyReasoningReplayRepairTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ModelVariant VARIANT =
      new ModelVariant("default", "medium", PromptCacheRetention.NONE, null);
  private static final String MODEL = "MiniMax-M2";

  private ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "minimax_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.example.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    ProviderDescriptor descriptor = descriptor();
    return new ProviderRequest(
        new ModelDescriptor(
            descriptor.providerName(), MODEL, MODEL, Set.of(ModelInputModality.TEXT), true, true),
        VARIANT,
        1024,
        "Test system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private ProviderResponse accumulate(String responseEvent) throws Exception {
    ProviderDescriptor descriptor = descriptor();
    ProviderRequest request = request(List.of());
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(request, descriptor, event -> {});
    accumulator.processEvent(MAPPER.readTree(responseEvent));
    return accumulator.response();
  }

  /**
   * Empty reasoning placeholders cannot carry semantic thinking and must not be frozen as native
   * blocks.
   */
  @Test
  void emptyReasoningPlaceholderFallsBackToSemanticBlocks() throws Exception {
    ProviderResponse response =
        accumulate(
            """
            {"type":"response.completed","response":{"id":"resp_empty","status":"completed",
             "usage":{"input_tokens":10,"output_tokens":5},"output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","content":[
               {"type":"output_text","text":"The fix is bounded."}]}]}}
            """);

    assertEquals("The fix is bounded.", response.text());
    assertEquals(1, response.blocks().size());
    ProviderTextBlock text = (ProviderTextBlock) response.blocks().get(0);
    assertNull(text.providerBlockData());
  }

  /**
   * Encrypted reasoning data survives the durable message codec and is replayed with its owner
   * block.
   */
  @Test
  void encryptedReasoningBlockSurvivesDurableRoundTripAndReplay() throws Exception {
    String event =
        """
        {"type":"response.completed","response":{"id":"resp_native","status":"completed",
         "usage":{"input_tokens":10,"output_tokens":5},"output":[
          {"id":"rs_native","type":"reasoning","encrypted_content":"opaque-cipher",
           "summary":[{"type":"summary_text","text":"Weigh the tradeoffs."}]},
          {"id":"msg_native","type":"message","role":"assistant","status":"completed",
           "content":[{"type":"output_text","text":"The fix is bounded.","annotations":[]}]}
         ]}}
        """;
    ProviderResponse response = accumulate(event);
    ProviderThinkingBlock thinking = (ProviderThinkingBlock) response.blocks().get(0);
    ProviderBlockData data = thinking.providerBlockData();
    assertNotNull(data);
    assertEquals(ProviderReplayFormat.OPENAI_RESPONSES, data.protocol());
    assertEquals("opaque-cipher", data.encryptedContent());
    assertEquals("rs_native", data.itemId());
    assertTrue(data.itemStart());

    MessagePayload payload =
        new BuiltinHistoryPayloadMapper().assistantPayload(response, List.of());
    HistoryEntryPayloadJsonCodec codec = new HistoryEntryPayloadJsonCodec();
    MessagePayload durable =
        (MessagePayload) codec.decode(EntryType.MESSAGE, codec.encode(payload));
    assertEquals(payload, durable);

    List<ProviderMessage> projected =
        ProviderMessageProjector.byNames(Set.of())
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent("continue")))),
                    ProviderMessageProjector.ProjectedMessage.of(durable.message())));
    JsonNode encoded =
        MAPPER
            .readTree(
                new OpenAiResponsesRequestEncoder()
                    .encode(request(projected), descriptor())
                    .bodyUtf8Bytes())
            .get("input");
    JsonNode expectedOutput = MAPPER.readTree(event).path("response").path("output");
    assertEquals(3, encoded.size());
    assertEquals(expectedOutput.get(0), encoded.get(1));
    assertEquals(expectedOutput.get(1), encoded.get(2));
  }
}
