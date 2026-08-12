package fun.fengwk.kkstudio.harness.runtime.model.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link ModelDescriptorJsonCodec} 的契约与 round-trip 测试，覆盖 descriptor 与 variant 完整字段、所有 strict
 * 拒绝路径、确定性 canonical 输出，并断言与 {@link ProviderRequestJsonCodec} 在 model/variant 子树上的 wire 一致性，防止未来漂移。
 */
class ModelDescriptorJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ModelDescriptorJsonCodec codec = new ModelDescriptorJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();

  // ---------- 往返 ----------

  /** 完整 ModelDescriptor 走完 codec 后必须等价。 */
  @Test
  void roundTripsFullDescriptor() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String json = codec.encodeDescriptor(descriptor);
    ModelDescriptor decoded = codec.decodeDescriptor(json);
    assertEquals(descriptor, decoded);
    assertEquals(json, codec.encodeDescriptor(decoded));
  }

  /** variant 完整字段 + nullable 字段必须按 wire 输出，且 decode 后保留 null。 */
  @Test
  void roundTripsVariantWithAllNullables() {
    ModelVariant variant =
        new ModelVariant("id", null, null, null, null, null, null, List.of(), null);
    String json = codec.encodeVariant(variant);
    ModelVariant decoded = codec.decodeVariant(json);
    assertEquals(variant, decoded);
  }

  /** Variant 含全 nullable 字段填充。 */
  @Test
  void roundTripsVariantWithFilledNullables() {
    ModelVariant variant =
        new ModelVariant("id", 100, 0.5, 0.9, 40, -0.1, 0.2, List.of("STOP", "END"), "high");
    String json = codec.encodeVariant(variant);
    ModelVariant decoded = codec.decodeVariant(json);
    assertEquals(variant, decoded);
  }

  // ---------- 确定性 / canonical ----------

  /** 同一 descriptor 多次 encode 必须产生 bit-identical JSON。 */
  @Test
  void encodingIsDeterministic() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String first = codec.encodeDescriptor(descriptor);
    String second = codec.encodeDescriptor(descriptor);
    assertEquals(first, second);
  }

  /** descriptor 字段顺序固定。 */
  @Test
  void descriptorFieldsAreInFixedOrder() throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(codec.encodeDescriptor(canonicalDescriptor()));
    ArrayNode names = NODES.arrayNode();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        List.of("providerName", "modelName", "inputModalities", "tools", "reasoning", "pricing"),
        List.of(
            names.get(0).asText(),
            names.get(1).asText(),
            names.get(2).asText(),
            names.get(3).asText(),
            names.get(4).asText(),
            names.get(5).asText()));
  }

  /** BigDecimal 字段以 plain 字符串输出。 */
  @Test
  void bigDecimalsAreEmittedAsPlainString() throws Exception {
    ModelDescriptor descriptor = canonicalDescriptor();
    JsonNode root = OBJECT_MAPPER.readTree(codec.encodeDescriptor(descriptor));
    JsonNode pricing = root.get("pricing");
    assertTrue(pricing.get("serviceTierMultiplier").isTextual());
    assertTrue(pricing.get("inputPerMillionTokens").isTextual());
    assertEquals(
        new BigDecimal("3.000000000000"),
        new BigDecimal(pricing.get("inputPerMillionTokens").asText()));
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

  /** 未知 pricing 字段必须拒绝。 */
  @Test
  void rejectsUnknownPricingField() {
    ObjectNode node = canonicalDescriptorNode();
    ObjectNode pricing = (ObjectNode) node.get("pricing");
    pricing.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 顶层不是 object 必须拒绝。 */
  @Test
  void rejectsNonObjectRoot() {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor("\"x\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor("42"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor("{"));
    assertThrows(NullPointerException.class, () -> codec.decodeDescriptor(null));
    assertThrows(NullPointerException.class, () -> codec.decodeDescriptorNode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeDescriptor(null));
    assertThrows(NullPointerException.class, () -> codec.encodeDescriptorNode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeVariant(null));
    assertThrows(NullPointerException.class, () -> codec.encodeVariantNode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeVariant(null));
    assertThrows(NullPointerException.class, () -> codec.decodeVariantNode(null));
  }

  /** trailing tokens 拒绝。 */
  @Test
  void rejectsTrailingTokens() throws Exception {
    ObjectNode node = canonicalDescriptorNode();
    String json = OBJECT_MAPPER.writeValueAsString(node) + " {}";
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor(json));
  }

  /** duplicate field 拒绝。 */
  @Test
  void rejectsDuplicateField() throws Exception {
    String json =
        "{"
            + "\"providerName\":\"provider\",\"providerName\":\"other\","
            + "\"modelName\":\"x\","
            + "\"inputModalities\":[\"TEXT\"],"
            + "\"tools\":false,\"reasoning\":false,"
            + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"t\","
            + "\"serviceTier\":\"s\",\"serviceTierMultiplier\":\"1\",\"version\":\"v\","
            + "\"inputPerMillionTokens\":\"0\",\"outputPerMillionTokens\":\"0\","
            + "\"cacheReadPerMillionTokens\":\"0\",\"cacheWritePerMillionTokens\":\"0\","
            + "\"cacheWriteLongPerMillionTokens\":\"0\",\"reasoningPerMillionTokens\":\"0\"}"
            + "}";
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor(json));
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
    wrongType.set("inputModalities", NODES.textNode("TEXT"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(wrongType));

    ObjectNode nonTextElement = canonicalDescriptorNode();
    nonTextElement.set("inputModalities", NODES.arrayNode().add(1));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(nonTextElement));

    ObjectNode blankElement = canonicalDescriptorNode();
    blankElement.set("inputModalities", NODES.arrayNode().add(" "));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(blankElement));
  }

  /** 旧五字段 durable 形态（缺 inputModalities）不做 dual-read，必须拒绝。 */
  @Test
  void rejectsLegacyDescriptorShapeWithoutInputModalities() {
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

  // ---------- 跨 codec 一致性 ----------

  /**
   * 关键交叉断言：ProviderRequest 内嵌的 model 与 variant 子节点必须与共享 codec 输出完全一致，确保未来修改 不会让两者漂移。两边都是直接 {@code
   * encodeNode} 产出，未经过 JSON parse，所以节点类型（IntNode / LongNode 等）天然一致，{@code JsonNode#equals} 可直接生效。
   */
  @Test
  void providerRequestModelAndVariantMatchSharedCodec() {
    ModelDescriptor model = canonicalDescriptor();
    ModelVariant variant =
        new ModelVariant(
            "balanced", 1024, 0.2, 0.8, 40, -0.1, 0.1, List.of("END", "STOP"), "medium");
    ProviderRequest request =
        new ProviderRequest(
            model,
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("hello")))),
            List.of(new ProviderToolDefinition("noop", "no-op", "{\"type\":\"object\"}")),
            ProviderCacheControl.none());

    JsonNode providerRoot = providerCodec.encodeNode(request);
    JsonNode sharedModel = codec.encodeDescriptorNode(model);
    JsonNode sharedVariant = codec.encodeVariantNode(variant);

    assertEquals(sharedModel, providerRoot.get("model"));
    assertEquals(sharedVariant, providerRoot.get("variant"));
  }

  /**
   * 端到端：canonical wire fixture 必须与 ProviderRequestJsonCodec 生成的 request JSON 完全一致，确保 shared codec
   * 抽取后 wire bit-identical。
   *
   * <p>{@code ProviderRequestJsonCodec.encode} 与共享 codec 必须对 model/variant 子树输出相同的 canonical
   * wire；同时保留通过 shared codec 反向解码的对象相等断言，确保两端语义等价。
   */
  @Test
  void sharedCodecMatchesCanonicalProviderFixture() throws Exception {
    ProviderRequest request = canonicalProviderRequest();
    String providerJson = providerCodec.encode(request);

    // 1) canonical wire 与共享 codec 文本输出逐字节一致（直接比较文本，无需 parse 再 toString）。
    JsonNode root = OBJECT_MAPPER.readTree(providerJson);
    String sharedModelJson = codec.encodeDescriptor(request.model());
    String sharedVariantJson = codec.encodeVariant(request.variant());
    assertEquals(sharedModelJson, root.get("model").toString());
    assertEquals(sharedVariantJson, root.get("variant").toString());

    // 2) 经过 parse 后的子树与共享 codec 直接 encodeNode 输出文本完全一致（canonical wire 一致）。
    assertEquals(
        codec.encodeDescriptorNode(request.model()).toString(), root.get("model").toString());
    assertEquals(
        codec.encodeVariantNode(request.variant()).toString(), root.get("variant").toString());

    // 3) 通过 shared codec 反向解码 model/variant 子树必须等于 request 中的对应字段。
    assertEquals(request.model(), codec.decodeDescriptorNode(root.get("model")));
    assertEquals(request.variant(), codec.decodeVariantNode(root.get("variant")));
  }

  // ---------- Fixture / 辅助方法 ----------

  private static ModelDescriptor canonicalDescriptor() {
    return new ModelDescriptor(
        "openai",
        "gpt-5-mini",
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
        true,
        true,
        canonicalPricing());
  }

  private static ModelPricing canonicalPricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        new BigDecimal("1.5"),
        "v1",
        new BigDecimal("3.000000000000"),
        new BigDecimal("6.000000000000"),
        new BigDecimal("0.300000000000"),
        new BigDecimal("3.750000000000"),
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ObjectNode canonicalDescriptorNode() {
    try {
      return (ObjectNode)
          OBJECT_MAPPER.readTree(
              new ModelDescriptorJsonCodec().encodeDescriptor(canonicalDescriptor()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * 与 {@code ProviderRequestJsonCodecTest.canonicalRequest()} 等价的 request，但仅用于交叉断言（不依赖该测试
   * 私有字段）。canonical fixture 由 ProviderRequestJsonCodecTest 维护，本测试只复用其形态。
   */
  private static ProviderRequest canonicalProviderRequest() {
    ModelDescriptor model = canonicalDescriptor();
    ModelVariant variant =
        new ModelVariant(
            "balanced", 1024, 0.2, 0.8, 40, -0.1, 0.1, List.of("END", "STOP"), "medium");
    return new ProviderRequest(
        model,
        variant,
        new ArrayList<>(),
        List.of(new ProviderToolDefinition("lookup", "Look up facts", "{}")),
        ProviderCacheControl.none());
  }
}
