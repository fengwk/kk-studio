package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolType;

/** 完整 frozen 的 Tool 调用请求 wire，包含逐字的原始参数 JSON。 */
class ToolInvocationRequestJsonCodecTest {

  private static final String ARGUMENTS_JSON = "{\n}";

  private final ToolInvocationRequestJsonCodec codec = new ToolInvocationRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  /** 精确输出与 round-trip 共同证明原始参数保持字符串形式而未被规范化。 */
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

  /** duplicate/trailing/非 object 的文档无法进入持久化的 request 字段。 */
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

  /** 嵌套精确字段与 JSON-object 语法被重新校验；schema 语义校验在 planner，codec 不重复。 */
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
        () -> codec.decode(json.replace("\"argumentsJson\":\"{}\"", "\"argumentsJson\":\"[]\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(json.replace("\"argumentsJson\":\"{}\"", "\"argumentsJson\":\"{} {}\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replace("\"call\":{", "\"call\":{\"unknown\":true,")));
  }

  /** toolName 与 binding 的语义匹配在 planner 校验，codec 只做语法级 round-trip。 */
  @Test
  void decodesToolNameMismatchAsSyntaxValidRequest() {
    String json =
        codec.encode(
            new ToolInvocationRequest(
                new ToolCall("call-1", "bash", "{}"), binding(ToolType.PLATFORM)));

    ToolInvocationRequest decoded =
        codec.decode(json.replace("\"toolName\":\"bash\"", "\"toolName\":\"other\""));
    assertEquals("other", decoded.call().toolName());
    assertEquals(binding(ToolType.PLATFORM), decoded.binding());
  }

  /** null binding 是 immediate FAILED 槽位的合法持久化形状，round-trip 保持为 null。 */
  @Test
  void roundTripsNullableBinding() {
    ToolInvocationRequest request =
        new ToolInvocationRequest(new ToolCall("call-1", "undeclared", "{}"), null);
    String expected =
        "{\"call\":{\"id\":\"call-1\",\"toolName\":\"undeclared\",\"argumentsJson\":\"{}\"},"
            + "\"binding\":null}";

    assertEquals(expected, codec.encode(request));
    assertEquals(request, codec.decode(expected));
    assertNull(codec.decode(expected).binding());
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
  }

  /** encode 重新校验原始 JSON，使内存值无法绕过严格持久化边界。 */
  @Test
  void encodeRejectsArgumentsWithTrailingJsonDocuments() {
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "bash", "{} {}"), binding(ToolType.PLATFORM));

    assertThrows(IllegalArgumentException.class, () -> codec.encode(request));
  }
}
