package fun.fengwk.kkstudio.harness.builtin.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesStreamAccumulator;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Producer-side validation rejects malformed known reasoning content without exposing secrets. */
class OpenAiResponsesReasoningContentReplayTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ProviderDescriptor SOURCE = descriptor("deepseek_test");
  private static final String MODEL = "synthetic-source-model";
  private static final List<String> MALFORMED_CONTENT_SHAPES =
      List.of(
          "null",
          "{}",
          "\"text\"",
          "1",
          "[null]",
          "[1]",
          "[\"text\"]",
          "[{}]",
          "[{\"type\":null,\"text\":\"x\"}]",
          "[{\"type\":1,\"text\":\"x\"}]",
          "[{\"type\":\"unknown\",\"text\":\"x\"}]",
          "[{\"type\":\"reasoning_text\"}]",
          "[{\"type\":\"reasoning_text\",\"text\":null}]",
          "[{\"type\":\"reasoning_text\",\"text\":1}]");

  private static Stream<Arguments> malformedContentShapes() {
    return MALFORMED_CONTENT_SHAPES.stream().map(Arguments::of);
  }

  private static ProviderDescriptor descriptor(String name) {
    return new ProviderDescriptor(
        name,
        ProviderType.OPENAI_RESPONSES,
        "https://api.example.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)));
  }

  private static ProviderRequest request() {
    return new ProviderRequest(
        new ModelDescriptor(
            SOURCE.providerName(), MODEL, MODEL, Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("default", "medium", PromptCacheRetention.NONE, null),
        1024,
        "Synthetic system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /**
   * Known malformed content in terminal output is rejected during capture, without echoing input.
   */
  @ParameterizedTest
  @MethodSource("malformedContentShapes")
  void malformedTerminalReasoningContentRejectedAtCapture(String contentJson) throws Exception {
    OpenAiResponsesStreamAccumulator accumulator = newAccumulator();
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> accumulator.processEvent(completedWithOutput(secretReasoningItem(contentJson))));
    assertCaptureRejection(error);
  }

  /** output_item.done validation is immediate rather than deferred until finish. */
  @ParameterizedTest
  @MethodSource("malformedContentShapes")
  void malformedDoneReasoningContentRejectedAtCapture(String contentJson) throws Exception {
    OpenAiResponsesStreamAccumulator accumulator = newAccumulator();
    ObjectNode done = MAPPER.createObjectNode().put("type", "response.output_item.done");
    done.set("item", secretReasoningItem(contentJson));
    ProviderException error =
        assertThrows(ProviderException.class, () -> accumulator.processEvent(done));
    assertCaptureRejection(error);
  }

  private static OpenAiResponsesStreamAccumulator newAccumulator() {
    return new OpenAiResponsesStreamAccumulator(request(), SOURCE, event -> {});
  }

  private static ObjectNode secretReasoningItem(String contentJson) throws Exception {
    ObjectNode item =
        MAPPER
            .createObjectNode()
            .put("type", "reasoning")
            .put("id", "rs_secret")
            .put("encrypted_content", "SECRET_CIPHER");
    item.putArray("summary").addObject().put("type", "summary_text").put("text", "SECRET_THOUGHT");
    item.set("content", MAPPER.readTree(contentJson));
    return item;
  }

  private static ObjectNode completedWithOutput(JsonNode reasoningItem) {
    ObjectNode terminal = MAPPER.createObjectNode().put("type", "response.completed");
    ObjectNode response = terminal.putObject("response");
    response.put("id", "resp_capture").put("status", "completed");
    ArrayNode output = response.putArray("output");
    output.add(reasoningItem);
    ObjectNode message = MAPPER.createObjectNode().put("type", "message").put("role", "assistant");
    message
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put("text", "Synthetic answer.");
    output.add(message);
    return terminal;
  }

  private static void assertCaptureRejection(ProviderException error) {
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertFalse(error.getMessage().contains("SECRET"));
  }
}
