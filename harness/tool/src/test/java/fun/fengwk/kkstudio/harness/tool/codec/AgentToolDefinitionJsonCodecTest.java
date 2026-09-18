package fun.fengwk.kkstudio.harness.tool.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link AgentToolDefinitionJsonCodec} 的严格 wire 契约、正负边界与 round-trip 测试。 */
class AgentToolDefinitionJsonCodecTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "Read a file",
          "read",
          new InputSchema("Read input", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  private final AgentToolDefinitionJsonCodec codec = new AgentToolDefinitionJsonCodec();
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();

  /** 两种 visibility 都必须保留完整值，且编码字段顺序固定为 descriptor/visibility；身份即 descriptor.name。 */
  @Test
  void roundTripsEveryDefinitionCombination() {
    List<AgentToolDefinition> definitions =
        List.of(
            new AgentToolDefinition(DESCRIPTOR, ToolVisibility.SELECTABLE),
            new AgentToolDefinition(DESCRIPTOR, ToolVisibility.INTERNAL));

    for (AgentToolDefinition original : definitions) {
      String encoded = codec.encode(original);
      String expected =
          "{\"descriptor\":"
              + descriptorCodec.encode(DESCRIPTOR)
              + ",\"visibility\":\""
              + original.visibility().name()
              + "\"}";
      assertEquals(expected, encoded);
      assertEquals(original, codec.decode(encoded));
      assertEquals(original, codec.decodeNode(codec.encodeNode(original)));
      assertEquals("read", codec.decode(encoded).descriptor().name());
    }
  }

  /** 字符串边界必须拒绝 Java null、空文档、非对象、duplicate field 与 trailing token。 */
  @Test
  void rejectsMalformedDocuments() {
    String valid = codec.encode(definition(ToolVisibility.SELECTABLE));
    String duplicate =
        valid.replace(
            "\"visibility\":\"SELECTABLE\"",
            "\"visibility\":\"SELECTABLE\",\"visibility\":\"INTERNAL\"");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(valid + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 顶层字段必须恰好是 descriptor/visibility；旧 id 字段与所有缺失、unknown、null 和错误类型都必须失败。 */
  @Test
  void rejectsInvalidFieldsAndEnumValues() {
    String valid = codec.encode(definition(ToolVisibility.SELECTABLE));
    String descriptor = descriptorCodec.encode(DESCRIPTOR);

    // 旧 wire 的 id 字段（AgentToolId）必须被当作未知字段拒绝，绝不兼容。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(valid.replace("{\"descriptor\"", "{\"id\":\"base.read\",\"descriptor\"")));

    // 未知字段
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(valid.replace("}", ",\"extra\":true}")));

    // 缺少字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"descriptor\":" + descriptor + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"visibility\":\"SELECTABLE\"", "")));

    // null 字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"descriptor\":" + descriptor, "\"descriptor\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":null")));

    // 类型错误
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(valid.replace(descriptor, "[]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":1")));

    // 非法 enum 值
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":\"OTHER\"")));
  }

  private static AgentToolDefinition definition(ToolVisibility visibility) {
    return new AgentToolDefinition(DESCRIPTOR, visibility);
  }
}
