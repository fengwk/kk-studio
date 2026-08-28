package fun.fengwk.kkstudio.harness.tool.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link AgentToolDefinitionJsonCodec} 的严格 wire 契约、正负边界与 round-trip 测试。 */
class AgentToolDefinitionJsonCodecTest {

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1.0",
          "Read a file",
          "read",
          new ToolParamsSchema("Read input", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  private final AgentToolDefinitionJsonCodec codec = new AgentToolDefinitionJsonCodec();
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();

  /** 三种 backend 与两种 visibility 都必须保留完整值，且编码字段顺序固定。 */
  @Test
  void roundTripsEveryDefinitionCombination() {
    List<AgentToolDefinition> definitions =
        List.of(
            definition("test.host-selectable", ToolVisibility.SELECTABLE, AgentToolBackend.HOST),
            definition("test.host-internal", ToolVisibility.INTERNAL, AgentToolBackend.HOST),
            definition(
                "test.declarative-selectable",
                ToolVisibility.SELECTABLE,
                AgentToolBackend.DECLARATIVE),
            definition(
                "test.declarative-internal", ToolVisibility.INTERNAL, AgentToolBackend.DECLARATIVE),
            definition(
                "test.environment-selectable",
                ToolVisibility.SELECTABLE,
                AgentToolBackend.ENVIRONMENT_CAPABILITY),
            definition(
                "test.environment-internal",
                ToolVisibility.INTERNAL,
                AgentToolBackend.ENVIRONMENT_CAPABILITY));

    for (AgentToolDefinition original : definitions) {
      String encoded = codec.encode(original);
      String expected =
          "{\"id\":\""
              + original.id().value()
              + "\",\"descriptor\":"
              + descriptorCodec.encode(DESCRIPTOR)
              + ",\"visibility\":\""
              + original.visibility().name()
              + "\",\"backend\":\""
              + original.backend().name()
              + "\"}";
      assertEquals(expected, encoded);
      assertEquals(original, codec.decode(encoded));
      assertEquals(original, codec.decodeNode(codec.encodeNode(original)));
    }
  }

  /** 字符串边界必须拒绝 Java null、空文档、非对象、duplicate field 与 trailing token。 */
  @Test
  void rejectsMalformedDocuments() {
    String valid =
        codec.encode(definition("test.host", ToolVisibility.SELECTABLE, AgentToolBackend.HOST));
    String duplicate =
        valid.replace("\"id\":\"test.host\"", "\"id\":\"test.host\",\"id\":\"test.other\"");

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

  /** 顶层字段必须恰好是 id/descriptor/visibility/backend，所有缺失、unknown、null 和错误类型都失败。 */
  @Test
  void rejectsInvalidFieldsAndEnumValues() {
    String valid =
        codec.encode(definition("test.host", ToolVisibility.SELECTABLE, AgentToolBackend.HOST));
    String descriptor = descriptorCodec.encode(DESCRIPTOR);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(",\"backend\":\"HOST\"", ",\"extra\":true,\"backend\":\"HOST\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"id\":\"test.host\",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"descriptor\":" + descriptor + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"visibility\":\"SELECTABLE\",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"backend\":\"HOST\"", "")));

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"id\":\"test.host\"", "\"id\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"descriptor\":" + descriptor, "\"descriptor\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"backend\":\"HOST\"", "\"backend\":null")));

    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(valid.replace("\"test.host\"", "1")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(valid.replace(descriptor, "[]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"backend\":\"HOST\"", "\"backend\":true")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace("\"visibility\":\"SELECTABLE\"", "\"visibility\":\"OTHER\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"backend\":\"HOST\"", "\"backend\":\"LOCAL\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"test.host\"", "\"Test.Host\"")));
  }

  private static AgentToolDefinition definition(
      String id, ToolVisibility visibility, AgentToolBackend backend) {
    return new AgentToolDefinition(new AgentToolId(id), DESCRIPTOR, visibility, backend);
  }
}
