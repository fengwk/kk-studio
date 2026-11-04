package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.util.List;

/** Replay 重建：已知项归一化、不透明项透传、草稿合成与失败边界。 */
class OpenAiResponsesReplayBuilderTest {

  @Test
  void normalizesKnownItemsAndKeepsOpaqueItems() {
    ObjectNode message = object();
    message.put("type", "message");
    message.put("id", 42);
    message.put("role", "user");
    message.putArray("content").addObject().put("type", "output_text").put("text", "answer");

    ObjectNode reasoning = object();
    reasoning.put("type", "reasoning");
    reasoning.put("id", 7);
    reasoning.put("summary", "plan");

    ObjectNode functionCall = object();
    functionCall.put("type", "function_call");
    functionCall.put("id", "call-1");
    functionCall.put("call_id", "call-1");
    functionCall.put("name", "lookup");
    functionCall.put("arguments", "{\"q\":1}");

    ObjectNode opaque = object();
    opaque.put("type", "web_search_call");
    opaque.put("id", "search-1");

    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(
            List.of(
                message, reasoning, functionCall, opaque, JsonNodeFactory.instance.stringNode("x")),
            false,
            "",
            "",
            List.of());

    assertEquals(4, output.size());
    ObjectNode normalizedMessage = (ObjectNode) output.get(0);
    assertEquals("assistant", normalizedMessage.path("role").asString(""));
    assertEquals("answer", normalizedMessage.path("content").get(0).path("text").asString(""));
    assertFalse(normalizedMessage.has("id"));
    assertEquals("summary_text", output.get(1).path("summary").get(0).path("type").asString(""));
    assertEquals("plan", output.get(1).path("summary").get(0).path("text").asString(""));
    assertEquals("lookup", output.get(2).path("name").asString(""));
    assertEquals("search-1", output.get(3).path("id").asString(""));
  }

  @Test
  void synthesizesFromStreamDraftWhenNoAuthoritativeItemsExist() {
    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(
            List.of(),
            false,
            "plan",
            "answer",
            List.of(
                new OpenAiResponsesReplayBuilder.ReplayTool(
                    "key", "id", "call_id", "lookup", "{\"q\":1}", true)));

    assertEquals(3, output.size());
    assertEquals("reasoning", output.get(0).path("type").asString(""));
    assertEquals("message", output.get(1).path("type").asString(""));
    ObjectNode functionCall = (ObjectNode) output.get(2);
    assertEquals("function_call", functionCall.path("type").asString(""));
    assertEquals("call_id", functionCall.path("call_id").asString(""));
    assertEquals("lookup", functionCall.path("name").asString(""));
    assertEquals("{\"q\":1}", functionCall.path("arguments").asString(""));
  }

  @Test
  void explicitTerminalOutputClearingSuppressesDraftSynthesis() {
    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(List.of(), true, "plan", "answer", List.of());

    assertTrue(output.isEmpty());
  }

  @Test
  void rejectsInvalidSynthesizedFunctionCall() {
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiResponsesReplayBuilder.build(
                    List.of(),
                    false,
                    "",
                    "",
                    List.of(
                        new OpenAiResponsesReplayBuilder.ReplayTool(
                            "key", "id", "call_id", null, "{}", true))));

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("invalid function call in replay construction", error.getMessage());
  }

  @Test
  void rejectsAuthoritativeFunctionCallWithoutRecoverableArguments() {
    ObjectNode item = object();
    item.put("type", "function_call");
    item.put("id", "call-1");
    item.put("name", "lookup");

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> OpenAiResponsesReplayBuilder.build(List.of(item), false, "", "", List.of()));

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("invalid function call in replay construction", error.getMessage());
  }

  @Test
  void findsReplayToolByKeyIdOrCallId() {
    OpenAiResponsesReplayBuilder.ReplayTool byKey =
        new OpenAiResponsesReplayBuilder.ReplayTool(
            "key", "id", "call_id", "lookup", "{\"q\":1}", true);
    ObjectNode item = object();
    item.put("type", "function_call");
    item.put("id", "key");

    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(List.of(item), false, "", "", List.of(byKey));

    assertEquals("key", output.get(0).path("call_id").asString(""));
    assertEquals("lookup", output.get(0).path("name").asString(""));
    assertEquals("{\"q\":1}", output.get(0).path("arguments").asString(""));
  }

  @Test
  void findsReplayToolByCallIdKey() {
    OpenAiResponsesReplayBuilder.ReplayTool tool =
        new OpenAiResponsesReplayBuilder.ReplayTool(
            "key", "id", "real-call", "lookup", "{\"q\":1}", true);
    ObjectNode item = object();
    item.put("type", "function_call");
    item.put("call_id", "key");

    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(List.of(item), false, "", "", List.of(tool));

    assertEquals("key", output.get(0).path("call_id").asString(""));
    assertEquals("lookup", output.get(0).path("name").asString(""));
    assertEquals("{\"q\":1}", output.get(0).path("arguments").asString(""));
  }

  @Test
  void findsReplayToolByLinearIdentityFallback() {
    OpenAiResponsesReplayBuilder.ReplayTool tool =
        new OpenAiResponsesReplayBuilder.ReplayTool(
            "key", "id", "real-call", "lookup", "{\"q\":1}", true);
    ObjectNode item = object();
    item.put("type", "function_call");
    item.put("id", "other-id");
    item.put("call_id", "real-call");

    ArrayNode output =
        OpenAiResponsesReplayBuilder.build(List.of(item), false, "", "", List.of(tool));

    assertEquals("real-call", output.get(0).path("call_id").asString(""));
    assertEquals("lookup", output.get(0).path("name").asString(""));
    assertEquals("{\"q\":1}", output.get(0).path("arguments").asString(""));
  }

  @Test
  void rejectsSynthesizedFunctionCallWithInvalidArguments() {
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                OpenAiResponsesReplayBuilder.build(
                    List.of(),
                    false,
                    "",
                    "",
                    List.of(
                        new OpenAiResponsesReplayBuilder.ReplayTool(
                            "key", "id", "call_id", "lookup", "not-json", true))));

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  @Test
  void rejectsAuthoritativeFunctionCallWithBlankIdentity() {
    ObjectNode item = object();
    item.put("type", "function_call");
    item.put("id", " ");
    item.put("name", "lookup");
    item.put("arguments", "{}");

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> OpenAiResponsesReplayBuilder.build(List.of(item), false, "", "", List.of()));

    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  @Test
  void opaqueItemsRequireNonBlankStringType() {
    assertTrue(OpenAiResponsesReplayBuilder.isOpaqueReplayItem(object().put("type", "custom")));
    assertFalse(OpenAiResponsesReplayBuilder.isOpaqueReplayItem(object()));
    assertFalse(OpenAiResponsesReplayBuilder.isOpaqueReplayItem(object().put("type", " ")));
    assertFalse(
        OpenAiResponsesReplayBuilder.isOpaqueReplayItem(JsonNodeFactory.instance.numberNode(1)));
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }
}
