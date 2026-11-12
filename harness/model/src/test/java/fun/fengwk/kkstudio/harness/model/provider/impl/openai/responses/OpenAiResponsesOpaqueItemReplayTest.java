package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Verifies that Responses native items are replayed from their owning ordered assistant blocks. */
class OpenAiResponsesOpaqueItemReplayTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", null, PromptCacheRetention.NONE, null);
  private static final String INSTRUCTION = "Test system instruction.";
  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  private static ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private static ModelDescriptor createModel() {
    return new ModelDescriptor(
        "openai_test", "gpt-5.4-mini", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true);
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return new ProviderRequest(
        createModel(),
        DEFAULT_VARIANT,
        1024,
        INSTRUCTION,
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  /**
   * Native extension items stay ordered with their owning block metadata during same-model replay.
   */
  @Test
  void preservesOpaqueItemsInBlockOrderAtMatchingAffinity() throws Exception {
    ProviderReplayAffinity affinity = createDescriptor().affinity("gpt-5.4-mini");
    ObjectNode searchFields = MAPPER.createObjectNode();
    searchFields
        .putObject("item")
        .put("status", "completed")
        .putObject("action")
        .put("type", "search")
        .put("query", "weather");
    ObjectNode messageFields = MAPPER.createObjectNode();
    messageFields.putObject("itemFields").put("status", "completed").put("phase", "final");
    ObjectNode imageFields = MAPPER.createObjectNode();
    imageFields.putObject("item").put("status", "completed").put("result", "aGVsbG8=");

    ProviderOpaqueBlock search =
        new ProviderOpaqueBlock(
            "web_search_call",
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                affinity,
                null,
                null,
                "ws_1",
                "web_search_call",
                null,
                false,
                null,
                true,
                searchFields));
    ProviderTextBlock message =
        new ProviderTextBlock(
            "sunny",
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                affinity,
                null,
                null,
                "msg_1",
                "message",
                "output_text",
                false,
                null,
                true,
                messageFields));
    ProviderOpaqueBlock image =
        new ProviderOpaqueBlock(
            "image_generation_call",
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                affinity,
                null,
                null,
                "img_1",
                "image_generation_call",
                null,
                false,
                null,
                true,
                imageFields));

    assertEquals("ws_1", search.providerBlockData().itemId());
    assertEquals(
        "weather",
        search
            .providerBlockData()
            .nativeFields()
            .path("item")
            .path("action")
            .path("query")
            .asString(""));
    assertEquals("output_text", message.providerBlockData().contentType());
    assertEquals(
        "completed",
        message.providerBlockData().nativeFields().path("itemFields").path("status").asString(""));
    assertEquals(
        "aGVsbG8=",
        image.providerBlockData().nativeFields().path("item").path("result").asString(""));

    ProviderMessage assistant =
        new ProviderMessage(ProviderMessageRole.ASSISTANT, List.of(search, message, image));
    JsonNode input =
        MAPPER
            .readTree(
                encoder.encode(request(List.of(assistant)), createDescriptor()).bodyUtf8Bytes())
            .path("input");
    assertEquals(3, input.size());
    assertEquals("web_search_call", input.get(0).path("type").asString(""));
    assertEquals("ws_1", input.get(0).path("id").asString(""));
    assertEquals("message", input.get(1).path("type").asString(""));
    assertEquals("sunny", input.get(1).path("content").get(0).path("text").asString(""));
    assertEquals("image_generation_call", input.get(2).path("type").asString(""));
    assertEquals("aGVsbG8=", input.get(2).path("result").asString(""));
  }

  /** A mismatched block affinity triggers semantic fallback and omits non-portable opaque items. */
  @Test
  void dropsOpaqueItemWhenAffinityDoesNotMatch() throws Exception {
    ObjectNode fields = MAPPER.createObjectNode();
    fields.putObject("item").put("status", "completed");
    ProviderOpaqueBlock search =
        new ProviderOpaqueBlock(
            "web_search_call",
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                createDescriptor().affinity("other-model"),
                null,
                null,
                "ws_1",
                "web_search_call",
                null,
                false,
                null,
                true,
                fields));
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(search, new ProviderTextBlock("sunny")));

    JsonNode input =
        MAPPER
            .readTree(
                encoder.encode(request(List.of(assistant)), createDescriptor()).bodyUtf8Bytes())
            .path("input");
    assertEquals(1, input.size());
    assertEquals("message", input.get(0).path("type").asString(""));
    assertEquals("sunny", input.get(0).path("content").get(0).path("text").asString(""));
    assertFalse(input.toString().contains("web_search_call"));
  }
}
