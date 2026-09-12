package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 对齐上游 PartsAndContentsMapper、FunctionMapper 与 FinishReason 契约的测试。 */
class GeminiRequestMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final GeminiRequestEncoder encoder = new GeminiRequestEncoder();
  private static final ModelVariant VARIANT = new ModelVariant("default");

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
        new UUID(1L, 2L));
  }

  private static ModelDescriptor model() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    return new ModelDescriptor(
        "google-test",
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        pricing);
  }

  /** 验证上游 PartsAndContentsMapper 的 user content 与 parts 映射规范。 */
  @Test
  void mapsUserContentAndPartsCorrectly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(),
            VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderTextBlock("part 1"), new ProviderTextBlock("part 2")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    ArrayNode contents = (ArrayNode) root.get("contents");
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asText());

    ArrayNode parts = (ArrayNode) contents.get(0).get("parts");
    assertEquals(2, parts.size());
    assertEquals("part 1", parts.get(0).get("text").asText());
    assertEquals("part 2", parts.get(1).get("text").asText());
  }

  /** 验证上游 FunctionMapper 的参数 schema 结构无损映射。 */
  @Test
  void mapsFunctionDefinitionsToGeminiFunctionDeclarations() throws Exception {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "city": { "type": "string", "description": "city name" },
            "days": { "type": "integer" }
          },
          "required": ["city"]
        }
        """;
    ProviderToolDefinition tool =
        new ProviderToolDefinition("forecast", "Weather forecast", schema);

    ProviderRequest request =
        new ProviderRequest(
            model(),
            VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Check weather")))),
            List.of(tool),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode fnDecl = root.get("tools").get(0).get("functionDeclarations").get(0);
    assertEquals("forecast", fnDecl.get("name").asText());
    assertEquals("Weather forecast", fnDecl.get("description").asText());

    JsonNode params = fnDecl.get("parameters");
    assertEquals("object", params.get("type").asText());
    assertEquals("string", params.get("properties").get("city").get("type").asText());
    assertEquals("integer", params.get("properties").get("days").get("type").asText());
    assertEquals("city", params.get("required").get(0).asText());
  }

  /** 验证多轮对话中，工具调用与工具返回被组织为符合上游期望的连续交互。 */
  @Test
  void mapsToolExecutionFlowMultiTurn() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(),
            VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Compute 1+1"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("call_add", "add", "{\"a\":1,\"b\":1}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_add", "add", List.of(new ProviderTextBlock("2")), false, "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    ArrayNode contents = (ArrayNode) root.get("contents");
    assertEquals(3, contents.size());

    assertEquals("user", contents.get(0).get("role").asText());
    assertEquals("model", contents.get(1).get("role").asText());
    assertEquals("user", contents.get(2).get("role").asText());

    JsonNode fnCall = contents.get(1).get("parts").get(0).get("functionCall");
    assertEquals("add", fnCall.get("name").asText());
    assertEquals("call_add", fnCall.get("id").asText());

    JsonNode fnResp = contents.get(2).get("parts").get(0).get("functionResponse");
    assertEquals("add", fnResp.get("name").asText());
    assertEquals("call_add", fnResp.get("id").asText());
    assertEquals("2", fnResp.get("response").get("result").asText());
  }

  /** 验证各种 finishReason 到 GenerationStopReason 的规范映射。 */
  @Test
  void mapsFinishReasonsCorrectly() throws Exception {
    ProviderStreamHandler noop =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    ProviderRequest req =
        new ProviderRequest(
            model(), VARIANT, 1024, List.of(), List.of(), ProviderCacheControl.none());

    // STOP -> COMPLETE
    GeminiStreamAccumulator accStop =
        new GeminiStreamAccumulator(
            req,
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));
    accStop.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
    assertEquals(GenerationStopReason.COMPLETE, accStop.finish().response().stopReason());

    // MAX_TOKENS -> LENGTH
    GeminiStreamAccumulator accLength =
        new GeminiStreamAccumulator(
            req,
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));
    accLength.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"MAX_TOKENS\"}]}");
    assertEquals(GenerationStopReason.LENGTH, accLength.finish().response().stopReason());

    // SAFETY -> FILTERED
    GeminiStreamAccumulator accSafety =
        new GeminiStreamAccumulator(
            req,
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));
    accSafety.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"SAFETY\"}]}");
    assertEquals(GenerationStopReason.FILTERED, accSafety.finish().response().stopReason());

    // RECITATION -> FILTERED
    GeminiStreamAccumulator accRecitation =
        new GeminiStreamAccumulator(
            req,
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));
    accRecitation.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"RECITATION\"}]}");
    assertEquals(GenerationStopReason.FILTERED, accRecitation.finish().response().stopReason());

    // IMAGE_RECITATION -> FILTERED
    GeminiStreamAccumulator accImageRecitation =
        new GeminiStreamAccumulator(
            req,
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(noop));
    accImageRecitation.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"IMAGE_RECITATION\"}]}");
    assertEquals(
        GenerationStopReason.FILTERED, accImageRecitation.finish().response().stopReason());
  }
}
