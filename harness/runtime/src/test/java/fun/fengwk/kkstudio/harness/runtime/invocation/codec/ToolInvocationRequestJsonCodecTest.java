package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolType;

/** Frozen Tool invocation request wire, including verbatim raw arguments JSON. */
class ToolInvocationRequestJsonCodecTest {

  private static final String ARGUMENTS_JSON = "{\n}";

  private final ToolInvocationRequestJsonCodec codec = new ToolInvocationRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  /**
   * Exact output and round-trip prove raw arguments remain a string rather than being normalized.
   */
  @Test
  void roundTripsCanonicalRequestWithoutNormalizingArgumentsJson() {
    ToolBinding binding = binding(ToolType.ENVIRONMENT);
    ToolInvocationRequest request =
        new ToolInvocationRequest(new ToolCall("call-1", "bash", ARGUMENTS_JSON), binding);
    String expected =
        "{\"call\":{\"id\":\"call-1\",\"toolName\":\"bash\",\"argumentsJson\":\"{\\n}\"},"
            + "\"binding\":"
            + bindingCodec.encode(binding)
            + "}";

    assertEquals(expected, codec.encode(request));
    assertEquals(request, codec.decode(expected));
    assertEquals(ARGUMENTS_JSON, codec.decode(expected).call().argumentsJson());
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
  }

  /** Duplicate/trailing/non-object documents cannot enter the durable request column. */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    ToolInvocationRequest request =
        new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), binding(ToolType.PLATFORM));
    String json = codec.encode(request);
    String duplicate = json.replace("\"call\":", "\"call\":null,\"call\":");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " false"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** Nested exact fields, JSON-object syntax and descriptor matching are all revalidated. */
  @Test
  void rejectsCorruptCallOrBindingFacts() {
    String json =
        codec.encode(
            new ToolInvocationRequest(
                new ToolCall("call-1", "bash", "{}"), binding(ToolType.PLATFORM)));

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"binding\":", "\"extra\":true,\"binding\":")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                json.replace(
                    ",\"binding\":" + bindingCodec.encode(binding(ToolType.PLATFORM)), "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"id\":\"call-1\"", "\"id\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                json.replace("\"argumentsJson\":\"{}\"", "\"argumentsJson\":{\"nested\":true}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"toolName\":\"bash\"", "\"toolName\":\"other\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"argumentsJson\":\"{}\"", "\"argumentsJson\":\"[]\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(json.replace("\"argumentsJson\":\"{}\"", "\"argumentsJson\":\"{} {}\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                json.replace(
                    "\"argumentsJson\":\"{}\"", "\"argumentsJson\":\"{\\\"extra\\\":1}\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"call\":{", "\"call\":{\"unknown\":true,")));
  }

  /** Encode rechecks raw JSON so in-memory values cannot bypass the strict persistence boundary. */
  @Test
  void encodeRejectsArgumentsWithTrailingJsonDocuments() {
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "bash", "{} {}"), binding(ToolType.PLATFORM));

    assertThrows(IllegalArgumentException.class, () -> codec.encode(request));
  }
}
