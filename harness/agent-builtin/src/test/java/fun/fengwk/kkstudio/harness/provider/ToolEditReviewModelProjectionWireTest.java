package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

class ToolEditReviewModelProjectionWireTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String API_KEY = "test-api-key";
  private static final ModelCallTimeoutPolicy TIMEOUT_POLICY =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10));

  private static final String TARGET_PATH = "src/main/java/com/example/App.java";
  private static final String DIFF_CONTENT = "@@ -1,3 +1,3 @@\n-line1\n+line1-modified";
  private static final String SHA256_HEX =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String EDIT_DETAILS_JSON =
      "{\"editReview\":{\"path\":\""
          + TARGET_PATH
          + "\",\"diff\":\""
          + DIFF_CONTENT.replace("\n", "\\n")
          + "\",\"sha256\":\""
          + SHA256_HEX
          + "\"}}";
  private static final String EDIT_OUTPUT_TEXT = "File edited successfully. Replacements: 1.";

  private interface AdapterFactory {
    ProviderAdapter create(JdkHttpSseTransport transport, String apiKey);
  }

  private enum Protocol {
    OPENAI_CHAT("openai-chat", ProviderType.OPENAI, OpenAiChatProviderAdapter::new),
    OPENAI_RESPONSES(
        "openai-responses", ProviderType.OPENAI_RESPONSES, OpenAiResponsesProviderAdapter::new),
    ANTHROPIC("anthropic", ProviderType.ANTHROPIC, AnthropicProviderAdapter::new),
    GEMINI("gemini", ProviderType.GOOGLE, GeminiProviderAdapter::new);

    private final String label;
    private final ProviderType providerType;
    private final AdapterFactory adapterFactory;

    Protocol(String label, ProviderType providerType, AdapterFactory adapterFactory) {
      this.label = label;
      this.providerType = providerType;
      this.adapterFactory = adapterFactory;
    }

    ProviderAdapter createAdapter(JdkHttpSseTransport transport) {
      return adapterFactory.create(transport, API_KEY);
    }

    ModelDescriptor model() {
      return new ModelDescriptor(
          label, "synthetic-model", "synthetic-model", Set.of(ModelInputModality.TEXT), true, true);
    }

    ProviderDescriptor descriptor() {
      return new ProviderDescriptor(
          label, providerType, "https://example.invalid/v1", TIMEOUT_POLICY);
    }
  }

  private HttpClient client;
  private ExecutorService workers;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    client = HttpClient.newHttpClient();
    workers = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDown() {
    workers.shutdownNow();
    scheduler.shutdownNow();
  }

  @Test
  void nativeToolResultIncludesDiffExactlyOnceAcrossFourProtocols() throws Exception {
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("Replace line1")));
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ToolCallMessageContent(
                    "call-edit-1",
                    "edit",
                    "edit",
                    "{\"path\":\"" + TARGET_PATH + "\"}",
                    null,
                    null)));
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-edit-1",
                    "edit",
                    "edit",
                    List.of(new TextMessageContent(EDIT_OUTPUT_TEXT)),
                    false,
                    EDIT_DETAILS_JSON)));

    List<ProviderMessage> messages =
        ProviderMessageProjector.byNames(Set.of("edit"))
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(user, null),
                    ProviderMessageProjector.ProjectedMessage.of(assistant, null),
                    ProviderMessageProjector.ProjectedMessage.of(tool, null)));

    verifyWireAcrossFourProtocols(messages, true);
  }

  @Test
  void downgradedToolResultIncludesDiffExactlyOnceAcrossFourProtocols() throws Exception {
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("Replace line1")));
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ToolCallMessageContent(
                    "call-edit-1",
                    "edit",
                    "edit",
                    "{\"path\":\"" + TARGET_PATH + "\"}",
                    "edit file",
                    "dev")));
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-edit-1",
                    "edit",
                    "edit",
                    List.of(new TextMessageContent(EDIT_OUTPUT_TEXT)),
                    false,
                    EDIT_DETAILS_JSON)));

    List<ProviderMessage> messages =
        ProviderMessageProjector.byNames(Set.of())
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(user, null),
                    ProviderMessageProjector.ProjectedMessage.of(assistant, null),
                    ProviderMessageProjector.ProjectedMessage.of(tool, null)));

    verifyWireAcrossFourProtocols(messages, false);
  }

  private void verifyWireAcrossFourProtocols(List<ProviderMessage> messages, boolean isNative)
      throws Exception {
    for (Protocol protocol : Protocol.values()) {
      ProviderAdapter adapter = protocol.createAdapter(transport());
      ProviderRequest request = request(protocol.model(), messages);
      JsonNode wire = MAPPER.readTree(adapter.encodeRequestBody(request, protocol.descriptor()));

      assertWireRequirements(wire, protocol.name());
      if (isNative) {
        assertNativeSpecificStructure(wire, protocol);
      } else {
        assertFallbackSpecificStructure(wire, protocol);
      }
    }
  }

  private void assertWireRequirements(JsonNode wire, String protocolName) {
    String wireString = wire.toString();
    assertEquals(
        1,
        countOccurrences(wireString, "-line1"),
        protocolName + " wire must contain the diff exactly once");
    assertEquals(
        1,
        countOccurrences(wireString, "+line1-modified"),
        protocolName + " wire must contain the diff exactly once");
    assertEquals(
        1,
        countOccurrences(wireString, "File: " + TARGET_PATH),
        protocolName + " wire must contain file path exactly once");
    assertTrue(
        wireString.contains(EDIT_OUTPUT_TEXT),
        protocolName + " wire must contain the original tool output");
    assertFalse(
        wireString.contains(SHA256_HEX), protocolName + " wire must never contain sha256 hash");
    assertFalse(
        wireString.contains("\"sha256\""), protocolName + " wire must never contain sha256 field");
    assertFalse(
        wireString.contains("\"editReview\""),
        protocolName + " wire must not serialize raw editReview object");
  }

  private void assertNativeSpecificStructure(JsonNode wire, Protocol protocol) {
    switch (protocol) {
      case OPENAI_CHAT -> {
        JsonNode chatTool = itemWithRole(wire.path("messages"), "tool");
        String chatContent = chatTool.path("content").asString("");
        assertTrue(chatContent.contains(EDIT_OUTPUT_TEXT));
        assertTrue(chatContent.contains("File: " + TARGET_PATH));
        assertTrue(chatContent.contains(DIFF_CONTENT));
      }
      case OPENAI_RESPONSES -> {
        JsonNode responsesInput = wire.path("input");
        JsonNode functionCallOutput = itemOfType(responsesInput, "function_call_output");
        String outputString = functionCallOutput.path("output").toString();
        assertTrue(outputString.contains(EDIT_OUTPUT_TEXT));
        assertTrue(outputString.contains("File: " + TARGET_PATH));
        assertTrue(outputString.contains("-line1"));
      }
      case ANTHROPIC -> {
        JsonNode toolResult = null;
        for (JsonNode message : wire.path("messages")) {
          for (JsonNode item : message.path("content")) {
            if ("tool_result".equals(item.path("type").asString(""))) {
              toolResult = item;
              break;
            }
          }
        }
        assertTrue(toolResult != null, "Anthropic wire must contain tool_result");
        String toolResultString = toolResult.path("content").toString();
        assertTrue(toolResultString.contains(EDIT_OUTPUT_TEXT));
        assertTrue(toolResultString.contains("File: " + TARGET_PATH));
        assertTrue(toolResultString.contains("-line1"));
      }
      case GEMINI -> {
        boolean foundFunctionResponse = false;
        for (JsonNode content : wire.path("contents")) {
          for (JsonNode part : content.path("parts")) {
            if (part.has("functionResponse")) {
              String partStr = part.toString();
              assertTrue(partStr.contains(EDIT_OUTPUT_TEXT));
              assertTrue(partStr.contains("File: " + TARGET_PATH));
              assertTrue(partStr.contains("-line1"));
              foundFunctionResponse = true;
            }
          }
        }
        assertTrue(foundFunctionResponse, "Gemini wire must contain functionResponse");
      }
    }
  }

  private void assertFallbackSpecificStructure(JsonNode wire, Protocol protocol) {
    String wireText = wire.toString();
    assertTrue(
        wireText.contains("Previous context:"),
        protocol.name() + " wire must contain fallback context header");
    assertTrue(
        wireText.contains("File: " + TARGET_PATH),
        protocol.name() + " fallback wire must contain file path");
    assertTrue(wireText.contains("-line1"), protocol.name() + " fallback wire must contain diff");
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = 0;
    while ((index = haystack.indexOf(needle, index)) != -1) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private JdkHttpSseTransport transport() {
    return new JdkHttpSseTransport(client, scheduler);
  }

  private static ProviderRequest request(ModelDescriptor model, List<ProviderMessage> messages) {
    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        "Synthetic system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static JsonNode itemOfType(JsonNode array, String type) {
    for (JsonNode item : array) {
      if (type.equals(item.path("type").asString(""))) {
        return item;
      }
    }
    throw new AssertionError("no item of type " + type + " in " + array);
  }

  private static JsonNode itemWithRole(JsonNode array, String role) {
    for (JsonNode item : array) {
      if (role.equals(item.path("role").asString(""))) {
        return item;
      }
    }
    throw new AssertionError("no item with role " + role + " in " + array);
  }
}
