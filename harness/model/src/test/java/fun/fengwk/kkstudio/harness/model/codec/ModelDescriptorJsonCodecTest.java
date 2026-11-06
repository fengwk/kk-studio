package fun.fengwk.kkstudio.harness.model.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link ModelDescriptorJsonCodec} 的契约与 round-trip 测试，覆盖 descriptor 与 variant 完整字段、所有 strict
 * 拒绝路径与确定性 canonical 输出。
 */
class ModelDescriptorJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** variant 原生协议选项 fixture：覆盖厂商私有字段、小数与超出 Integer 范围的整数。 */
  private static final String CANONICAL_VARIANT_OPTIONS_JSON =
      "{\"vendor_extension\":\"kept\",\"ratio\":1.10,\"revision\":1758880000000,"
          + "\"thinking\":{\"budget_tokens\":4096}}";

  private final ModelDescriptorJsonCodec codec = new ModelDescriptorJsonCodec();

  private ModelDescriptor decodeDescriptor(String json) {
    if (json == null) {
      throw new NullPointerException("json");
    }
    try {
      return codec.decodeDescriptorNode(HarnessJson.STRICT_CANONICAL.readTree(json));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("malformed model descriptor JSON", exception);
    }
  }

  private ModelVariant decodeVariant(String json) {
    if (json == null) {
      throw new NullPointerException("json");
    }
    try {
      return codec.decodeVariantNode(HarnessJson.STRICT_CANONICAL.readTree(json));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("malformed model variant JSON", exception);
    }
  }

  // ---------- 往返 ----------

  /** 完整 ModelDescriptor 走完 codec 后必须等价。 */
  @Test
  void roundTripsFullDescriptor() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String json = codec.encodeDescriptorNode(descriptor).toString();
    ModelDescriptor decoded = decodeDescriptor(json);
    assertEquals(descriptor, decoded);
    assertEquals(json, codec.encodeDescriptorNode(decoded).toString());
  }

  /** variant 完整字段 + nullable 字段必须按 wire 输出，且 decode 后保留 null。 */
  @Test
  void roundTripsVariantWithAllNullables() {
    ModelVariant variant = new ModelVariant("id", null, PromptCacheRetention.NONE, null);
    String json = codec.encodeVariantNode(variant).toString();
    ModelVariant decoded = decodeVariant(json);
    assertEquals(variant, decoded);
  }

  /** Variant 含全 nullable 字段填充。 */
  @Test
  void roundTripsVariantWithFilledNullables() {
    ModelVariant variant = new ModelVariant("id", "high", PromptCacheRetention.SHORT, null);
    String json = codec.encodeVariantNode(variant).toString();
    ModelVariant decoded = decodeVariant(json);
    assertEquals(variant, decoded);
  }

  /** 厂商自定义 reasoningEffort（如 MAX、xHigh、hiGh）走完 codec 必须等价往返并原样保留大小写，仅去除首尾空白。 */
  @Test
  void roundTripsVariantWithCustomReasoningEfforts() {
    ModelVariant max = new ModelVariant("id", "MAX", PromptCacheRetention.NONE, null);
    assertEquals(max, decodeVariant(codec.encodeVariantNode(max).toString()));

    ModelVariant xhigh = new ModelVariant("id", "xHigh", PromptCacheRetention.SHORT, null);
    assertEquals(xhigh, decodeVariant(codec.encodeVariantNode(xhigh).toString()));

    ModelVariant mixed = new ModelVariant("id", "HiGh", PromptCacheRetention.LONG, null);
    assertEquals(mixed, decodeVariant(codec.encodeVariantNode(mixed).toString()));

    // 外层空白在解码时被去除，大小写保持原值
    ModelVariant decodedFromTrimmed =
        decodeVariant(
            "{\"id\":\"id\",\"reasoningEffort\":\"  MAX \",\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":{}}");
    assertEquals("MAX", decodedFromTrimmed.reasoningEffort());

    // 空白与超长 reasoningEffort 必须在解码时被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":\"   \",\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":\""
                    + "a".repeat(65)
                    + "\",\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":{}}"));
  }

  /** 原生协议选项必须作为嵌套 object 往返：厂商私有字段、整数与小数原样保留，且空选项仍输出 {@code {}}。 */
  @Test
  void roundTripsVariantWithProtocolOptions() throws Exception {
    ModelVariant variant =
        new ModelVariant(
            "id",
            "high",
            PromptCacheRetention.SHORT,
            new ProviderProtocolOptions(CANONICAL_VARIANT_OPTIONS_JSON));

    String json = codec.encodeVariantNode(variant).toString();
    ModelVariant decoded = decodeVariant(json);

    assertEquals(variant, decoded);
    assertEquals(json, codec.encodeVariantNode(decoded).toString());

    JsonNode protocolOptions = OBJECT_MAPPER.readTree(json).get("protocolOptions");
    assertTrue(protocolOptions.isObject());
    assertEquals("kept", protocolOptions.path("vendor_extension").asText());
    assertEquals("1.1", protocolOptions.path("ratio").asText());
    assertEquals(
        List.of("id", "reasoningEffort", "promptCacheRetention", "protocolOptions"),
        List.copyOf(fieldNames(OBJECT_MAPPER.readTree(json))));

    JsonNode emptyOptions =
        codec
            .encodeVariantNode(new ModelVariant("id", null, PromptCacheRetention.NONE, null))
            .get("protocolOptions");
    assertTrue(emptyOptions.isObject());
    assertTrue(emptyOptions.isEmpty());
  }

  /** protocolOptions 是 variant 的必填字段：缺失、null、非 object 以及内部重复键都必须拒绝。 */
  @Test
  void rejectsInvalidVariantProtocolOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":null,\"promptCacheRetention\":\"SHORT\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":null,\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":null,\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":[{\"a\":1}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decodeVariant(
                "{\"id\":\"id\",\"reasoningEffort\":null,\"promptCacheRetention\":\"SHORT\",\"protocolOptions\":{\"a\":1,\"a\":2}}"));
  }

  // ---------- 确定性 / canonical ----------

  /** 同一 descriptor 多次 encode 必须产生 bit-identical JSON。 */
  @Test
  void encodingIsDeterministic() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String first = codec.encodeDescriptorNode(descriptor).toString();
    String second = codec.encodeDescriptorNode(descriptor).toString();
    assertEquals(first, second);
  }

  /** descriptor 字段顺序固定。 */
  @Test
  void descriptorFieldsAreInFixedOrder() throws Exception {
    JsonNode root = codec.encodeDescriptorNode(canonicalDescriptor());
    ArrayNode names = NODES.arrayNode();
    root.propertyNames().forEach(names::add);
    assertEquals(
        List.of("providerName", "modelName", "modelId", "inputModalities", "tools", "reasoning"),
        List.of(
            names.get(0).asText(),
            names.get(1).asText(),
            names.get(2).asText(),
            names.get(3).asText(),
            names.get(4).asText(),
            names.get(5).asText()));
  }

  // ---------- 严格拒绝 ----------

  /** 未知 descriptor 字段必须拒绝。 */
  @Test
  void rejectsUnknownDescriptorField() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 缺失 descriptor 必填字段必须拒绝。 */
  @Test
  void rejectsMissingDescriptorField() {
    ObjectNode node = canonicalDescriptorNode();
    node.remove("modelName");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 错误类型字段必须拒绝。 */
  @Test
  void rejectsWrongTypedDescriptorField() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("tools", "true");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 空 provider name 必须拒绝。 */
  @Test
  void rejectsInvalidProviderName() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("providerName", "");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 未知 variant 字段必须拒绝。 */
  @Test
  void rejectsUnknownVariantField() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 顶层不是 object 必须拒绝。 */
  @Test
  void rejectsNonObjectRoot() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeDescriptorNode(NODES.arrayNode()));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeDescriptorNode(NODES.textNode("x")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeDescriptorNode(NODES.numberNode(42)));
    assertThrows(NullPointerException.class, () -> codec.decodeDescriptorNode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeDescriptorNode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeVariantNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeVariantNode(NODES.arrayNode()));
    assertThrows(NullPointerException.class, () -> codec.decodeVariantNode(null));
  }

  /** trailing tokens 拒绝。 */
  @Test
  void rejectsTrailingTokens() throws Exception {
    ObjectNode node = canonicalDescriptorNode();
    String json = OBJECT_MAPPER.writeValueAsString(node) + " {}";
    assertThrows(IllegalArgumentException.class, () -> decodeDescriptor(json));
  }

  /** duplicate field 拒绝。 */
  @Test
  void rejectsDuplicateField() throws Exception {
    String json =
        "{"
            + "\"providerName\":\"provider\",\"providerName\":\"other\","
            + "\"modelName\":\"x\","
            + "\"inputModalities\":[\"TEXT\"],"
            + "\"tools\":false,\"reasoning\":false"
            + "}";
    assertThrows(IllegalArgumentException.class, () -> decodeDescriptor(json));
  }

  /** inputModalities 必须是非空、无重复的合法 enum 字符串数组。 */
  @Test
  void rejectsInvalidInputModalities() {
    ObjectNode unknownEnum = canonicalDescriptorNode();
    unknownEnum.set("inputModalities", NODES.arrayNode().add("TEXT").add("FOREVER"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(unknownEnum));

    ObjectNode duplicate = canonicalDescriptorNode();
    duplicate.set("inputModalities", NODES.arrayNode().add("TEXT").add("TEXT"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(duplicate));

    ObjectNode empty = canonicalDescriptorNode();
    empty.set("inputModalities", NODES.arrayNode());
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(empty));

    ObjectNode wrongType = canonicalDescriptorNode();
    wrongType.set("inputModalities", NODES.stringNode("TEXT"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(wrongType));

    ObjectNode nonTextElement = canonicalDescriptorNode();
    nonTextElement.set("inputModalities", NODES.arrayNode().add(1));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(nonTextElement));

    ObjectNode blankElement = canonicalDescriptorNode();
    blankElement.set("inputModalities", NODES.arrayNode().add(" "));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(blankElement));
  }

  /** inputModalities 是 descriptor 的必填 durable 字段。 */
  @Test
  void rejectsDescriptorWithoutInputModalities() {
    ObjectNode node = canonicalDescriptorNode();
    node.remove("inputModalities");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 边界：providerName 必须为非空、无首尾空白的字符串。 */
  @Test
  void rejectsProviderNameBoundary() {
    ObjectNode zero = canonicalDescriptorNode();
    zero.put("providerName", "");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(zero));
    ObjectNode neg = canonicalDescriptorNode();
    neg.put("providerName", " provider");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(neg));
    ObjectNode str = canonicalDescriptorNode();
    str.put("providerName", 10);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(str));
  }

  // 档位是 durable variant 的必填字段，缺失、null 和未知值都不可被默认填充。
  @Test
  void requiresValidPromptCacheRetention() {
    for (PromptCacheRetention retention : PromptCacheRetention.values()) {
      ModelVariant value = new ModelVariant("v", null, retention, null);
      assertEquals(value, codec.decodeVariantNode(codec.encodeVariantNode(value)));
    }
    ObjectNode missing =
        codec.encodeVariantNode(new ModelVariant("v", null, PromptCacheRetention.SHORT, null));
    missing.remove("promptCacheRetention");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeVariantNode(missing));
    missing.putNull("promptCacheRetention");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeVariantNode(missing));
    missing.put("promptCacheRetention", "FOREVER");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeVariantNode(missing));
  }

  // ---------- Fixture / 辅助方法 ----------

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private static ModelDescriptor canonicalDescriptor() {
    return new ModelDescriptor(
        "openai",
        "gpt-5-mini",
        "gpt-5-mini",
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
        true,
        true);
  }

  private static ObjectNode canonicalDescriptorNode() {
    return new ModelDescriptorJsonCodec().encodeDescriptorNode(canonicalDescriptor());
  }
}
