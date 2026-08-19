package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

/** 最小严格 ToolCall wire codec（PostgreSQL tool invocation 持久化用）。 */
class ToolCallJsonCodecTest {

  private final ToolCallJsonCodec codec = new ToolCallJsonCodec();

  /** 精确 JSON 既证明字段顺序 deterministic，又在 round-trip 时重新执行领域约束校验。 */
  @Test
  void roundTripsWithCanonicalJson() {
    ToolCall call = new ToolCall("call-1", "bash", "{\"command\":\"ls\"}");
    String json =
        "{\"id\":\"call-1\",\"toolName\":\"bash\",\"argumentsJson\":\"{\\\"command\\\":\\\"ls\\\"}\"}";
    assertEquals(json, codec.encode(call));
    assertEquals(call, codec.decode(json));
    assertEquals(call, codec.decodeNode(codec.encodeNode(call)));
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(new ToolCall("call-1", "bash", "{}"));
    String duplicate =
        json.replace("\"toolName\":\"bash\"", "\"toolName\":\"bash\",\"toolName\":\"ls\"");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** argumentsJson 必须是合法 JSON object（非 object 或畸形 JSON 拒绝）。 */
  @Test
  void rejectsNonObjectOrMalformedArguments() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("call-1", "bash", "[]"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("call-1", "bash", "{"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"id\":\"call-1\",\"toolName\":\"bash\",\"argumentsJson\":\"not-json\"}"));
  }
}
