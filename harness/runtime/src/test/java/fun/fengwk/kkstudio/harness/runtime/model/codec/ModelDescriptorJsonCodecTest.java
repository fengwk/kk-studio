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
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * {@link ModelDescriptorJsonCodec} 的契约与 round-trip 测试，覆盖 descriptor 与 variant 完整字段、所有 strict
 * 拒绝路径、确定性 canonical 输出，并断言与 {@link ProviderRequestJsonCodec} 在 model/variant 子树上的 wire 一致性，防止未来漂移。
 */
class ModelDescriptorJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ModelDescriptorJsonCodec codec = new ModelDescriptorJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();

  // ---------- Round-trip ----------

  /** 完整 ModelDescriptor 走完 codec 后必须等价。 */
  @Test
  void roundTripsFullDescriptor() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String json = codec.encodeDescriptor(descriptor);
    ModelDescriptor decoded = codec.decodeDescriptor(json);
    assertEquals(descriptor, decoded);
    assertEquals(json, codec.encodeDescriptor(decoded));
  }

  /** 空 enum modalities + 空 variants + 空 stopSequences 的 descriptor 必须等价。 */
  @Test
  void roundTripsEmptyCollections() {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            1,
            2,
            ProviderType.OPENAI,
            "m",
            "d",
            100,
            50,
            EnumSet.of(ModelInputModality.TEXT),
            false,
            false,
            List.of(variant),
            new ModelPricing(
                "USD",
                "t",
                "s",
                BigDecimal.ONE,
                "v",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());

    String json = codec.encodeDescriptor(descriptor);
    ModelDescriptor decoded = codec.decodeDescriptor(json);
    assertEquals(descriptor, decoded);
  }

  /** Variant 完整字段 + nullable 字段必须按 wire 输出，且 decode 后保留 null。 */
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

  // ---------- Deterministic / canonical ----------

  /** 同一 descriptor 多次 encode 必须产生 bit-identical JSON。 */
  @Test
  void encodingIsDeterministic() {
    ModelDescriptor descriptor = canonicalDescriptor();
    String first = codec.encodeDescriptor(descriptor);
    String second = codec.encodeDescriptor(descriptor);
    assertEquals(first, second);
  }

  /** Set<Enum> 字段按 enum name 排序输出。 */
  @Test
  void enumSetsAreSortedByName() throws Exception {
    ModelDescriptor descriptor =
        new ModelDescriptor(
            10,
            20,
            ProviderType.ANTHROPIC,
            "claude",
            "Claude",
            200_000,
            8_192,
            EnumSet.of(
                ModelInputModality.VIDEO,
                ModelInputModality.TEXT,
                ModelInputModality.AUDIO,
                ModelInputModality.IMAGE,
                ModelInputModality.DOCUMENT),
            true,
            true,
            List.of(),
            new ModelPricing(
                "USD",
                "t",
                "s",
                BigDecimal.ONE,
                "v",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            new PromptCachePolicy(
                PromptCacheCapability.breakpoints(
                    EnumSet.of(PromptCacheRetention.LONG, PromptCacheRetention.SHORT),
                    EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM)),
                PromptCacheRetention.LONG));

    String json = codec.encodeDescriptor(descriptor);
    JsonNode root = OBJECT_MAPPER.readTree(json);
    ArrayNode modalities = (ArrayNode) root.get("inputModalities");
    assertEquals(
        List.of("AUDIO", "DOCUMENT", "IMAGE", "TEXT", "VIDEO"),
        List.of(
            modalities.get(0).asText(),
            modalities.get(1).asText(),
            modalities.get(2).asText(),
            modalities.get(3).asText(),
            modalities.get(4).asText()));

    ArrayNode retentions =
        (ArrayNode) root.get("promptCachePolicy").get("capability").get("supportedRetentions");
    assertEquals(
        List.of("LONG", "SHORT"), List.of(retentions.get(0).asText(), retentions.get(1).asText()));
    ArrayNode breakpoints =
        (ArrayNode) root.get("promptCachePolicy").get("capability").get("supportedBreakpoints");
    assertEquals(
        List.of("SYSTEM", "TOOLS"),
        List.of(breakpoints.get(0).asText(), breakpoints.get(1).asText()));
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

  /** descriptor 字段顺序固定。 */
  @Test
  void descriptorFieldsAreInFixedOrder() throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(codec.encodeDescriptor(canonicalDescriptor()));
    ArrayNode names = NODES.arrayNode();
    root.fieldNames().forEachRemaining(names::add);
    assertEquals(
        List.of(
            "providerResourceId",
            "modelResourceId",
            "providerType",
            "modelId",
            "displayName",
            "contextWindow",
            "maxOutputTokens",
            "inputModalities",
            "tools",
            "reasoning",
            "variants",
            "pricing",
            "promptCachePolicy"),
        List.of(
            names.get(0).asText(),
            names.get(1).asText(),
            names.get(2).asText(),
            names.get(3).asText(),
            names.get(4).asText(),
            names.get(5).asText(),
            names.get(6).asText(),
            names.get(7).asText(),
            names.get(8).asText(),
            names.get(9).asText(),
            names.get(10).asText(),
            names.get(11).asText(),
            names.get(12).asText()));
  }

  // ---------- Strict rejection ----------

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
    node.remove("displayName");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 错误类型字段必须拒绝。 */
  @Test
  void rejectsWrongTypedDescriptorField() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("contextWindow", "100");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 非正整数字段必须拒绝。 */
  @Test
  void rejectsNonPositiveProviderResourceId() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("providerResourceId", 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** maxOutputTokens > contextWindow 必须拒绝。 */
  @Test
  void rejectsMaxOutputTokensExceedingContextWindow() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("contextWindow", 100);
    node.put("maxOutputTokens", 101);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 未知 providerType 必须拒绝。 */
  @Test
  void rejectsUnknownProviderType() {
    ObjectNode node = canonicalDescriptorNode();
    node.put("providerType", "LEGACY");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** inputModalities 非 string 元素必须拒绝。 */
  @Test
  void rejectsNonStringModality() {
    ObjectNode node = canonicalDescriptorNode();
    ArrayNode mods = (ArrayNode) node.get("inputModalities");
    mods.set(0, NODES.numberNode(1));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 未知 modality 名必须拒绝。 */
  @Test
  void rejectsUnknownModality() {
    ObjectNode node = canonicalDescriptorNode();
    ArrayNode mods = (ArrayNode) node.get("inputModalities");
    mods.set(0, NODES.textNode("HOLOGRAM"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** inputModalities 含重复 enum name 必须拒绝（TreeSet 不能静默吞掉）。 */
  @Test
  void rejectsDuplicateModality() {
    ObjectNode node = canonicalDescriptorNode();
    ArrayNode mods = (ArrayNode) node.get("inputModalities");
    mods.add(NODES.textNode("TEXT"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** nested supportedRetentions 含重复 enum name 必须拒绝。 */
  @Test
  void rejectsDuplicateSupportedRetention() {
    ObjectNode node = canonicalDescriptorNode();
    ObjectNode capability = (ObjectNode) node.get("promptCachePolicy").get("capability");
    ArrayNode retentions = (ArrayNode) capability.get("supportedRetentions");
    retentions.add(NODES.textNode("SHORT"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** nested supportedBreakpoints 含重复 enum name 必须拒绝。 */
  @Test
  void rejectsDuplicateSupportedBreakpoint() {
    ObjectNode node = canonicalDescriptorNode();
    ObjectNode capability = (ObjectNode) node.get("promptCachePolicy").get("capability");
    ArrayNode breakpoints = (ArrayNode) capability.get("supportedBreakpoints");
    breakpoints.add(NODES.textNode("SYSTEM"));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 即便重复 enum 名合法（如 TEXT 出现两次），也必须拒绝，与 set 语义保持一致。 */
  @Test
  void rejectsDuplicateEnumEvenWhenAllAreKnown() {
    ObjectNode node = canonicalDescriptorNode();
    ArrayNode mods = (ArrayNode) node.get("inputModalities");
    mods.add(NODES.textNode("AUDIO"));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  /** 未知 variant 字段必须拒绝。 */
  @Test
  void rejectsUnknownVariantField() {
    ObjectNode node = canonicalDescriptorNode();
    ((ObjectNode) node.get("variants").get(0)).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 缺失 variant 字段必须拒绝。 */
  @Test
  void rejectsMissingVariantField() {
    ObjectNode node = canonicalDescriptorNode();
    ((ObjectNode) node.get("variants").get(0)).remove("id");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** variant 错误类型必须拒绝。 */
  @Test
  void rejectsWrongTypedVariantField() {
    ObjectNode node = canonicalDescriptorNode();
    ((ObjectNode) node.get("variants").get(0)).put("temperature", "0.2");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** variant 非有限 double 必须拒绝。 */
  @Test
  void rejectsNonFiniteVariantDouble() {
    ObjectNode node = canonicalDescriptorNode();
    ((ObjectNode) node.get("variants").get(0)).put("temperature", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** variant stopSequences 含空白字符串必须拒绝。 */
  @Test
  void rejectsBlankStopSequence() {
    ObjectNode node = canonicalDescriptorNode();
    ArrayNode stops = (ArrayNode) node.get("variants").get(0).get("stopSequences");
    stops.set(0, NODES.textNode(" "));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 未知 pricing 字段必须拒绝。 */
  @Test
  void rejectsUnknownPricingField() {
    ObjectNode node = canonicalDescriptorNode();
    node.get("pricing").getClass(); // sanity
    ObjectNode pricing = (ObjectNode) node.get("pricing");
    pricing.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(node));
  }

  /** 未知 prompt cache mode 必须拒绝。 */
  @Test
  void rejectsUnknownPromptCacheMode() {
    ObjectNode node = canonicalDescriptorNode();
    node.get("promptCachePolicy").get("capability");
    ObjectNode capability = (ObjectNode) node.get("promptCachePolicy").get("capability");
    capability.put("mode", "MANUAL");
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
            + "\"providerResourceId\":1,\"providerResourceId\":2,"
            + "\"modelResourceId\":3,\"providerType\":\"OPENAI\",\"modelId\":\"x\","
            + "\"displayName\":\"d\",\"contextWindow\":100,\"maxOutputTokens\":50,"
            + "\"inputModalities\":[\"TEXT\"],\"tools\":false,\"reasoning\":false,"
            + "\"variants\":[],\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"t\","
            + "\"serviceTier\":\"s\",\"serviceTierMultiplier\":\"1\",\"version\":\"v\","
            + "\"inputPerMillionTokens\":\"0\",\"outputPerMillionTokens\":\"0\","
            + "\"cacheReadPerMillionTokens\":\"0\",\"cacheWritePerMillionTokens\":\"0\","
            + "\"cacheWriteLongPerMillionTokens\":\"0\",\"reasoningPerMillionTokens\":\"0\"},"
            + "\"promptCachePolicy\":{\"capability\":{\"mode\":\"UNKNOWN\","
            + "\"supportedRetentions\":[],\"supportedBreakpoints\":[]},"
            + "\"retention\":\"NONE\"}"
            + "}";
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptor(json));
  }

  /** 边界：providerResourceId 必须正整数（0 / 负数 / 字符串拒绝）。 */
  @Test
  void rejectsProviderResourceIdBoundary() {
    assertRejectsNumericBoundary("providerResourceId");
  }

  /** 边界：contextWindow 必须正整数。 */
  @Test
  void rejectsContextWindowBoundary() {
    assertRejectsNumericBoundary("contextWindow");
  }

  /** 边界：maxOutputTokens 必须正整数。 */
  @Test
  void rejectsMaxOutputTokensBoundary() {
    assertRejectsNumericBoundary("maxOutputTokens");
  }

  private void assertRejectsNumericBoundary(String field) {
    ObjectNode zero = canonicalDescriptorNode();
    zero.put(field, 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(zero));
    ObjectNode neg = canonicalDescriptorNode();
    neg.put(field, -1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(neg));
    ObjectNode str = canonicalDescriptorNode();
    str.put(field, "10");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeDescriptorNode(str));
  }

  // ---------- Cross-codec consistency ----------

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
   * <p>{@code ProviderRequestJsonCodec.encode} 输出的 JSON 经 parse 后，整型字段（{@code providerResourceId} /
   * {@code modelResourceId} / {@code contextWindow} 等）会以 Jackson 默认的最小宽度 numeric node 表示（{@code
   * IntNode}），而共享 codec 的直接 encodeNode 路径会保留 {@code LongNode}； 两棵 {@link JsonNode} 树虽然文本完全一致，但
   * {@code JsonNode#equals} 在不同 numeric node 子类上会返回 {@code false}。因此本测试只比较 canonical serialized
   * wire 文本；同时保留通过 shared codec 反向解码的 对象相等断言，确保两端语义等价。
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

  // ---------- Fixture / helpers ----------

  private static ModelDescriptor canonicalDescriptor() {
    return new ModelDescriptor(
        1001L,
        2002L,
        ProviderType.OPENAI,
        "gpt-5-mini",
        "GPT-5 Mini",
        200_000L,
        16_384L,
        EnumSet.of(
            ModelInputModality.TEXT,
            ModelInputModality.IMAGE,
            ModelInputModality.AUDIO,
            ModelInputModality.VIDEO,
            ModelInputModality.DOCUMENT),
        true,
        true,
        List.of(
            new ModelVariant("default", null, 0.7, 0.9, null, null, null, List.of("STOP"), null)),
        canonicalPricing(),
        PromptCachePolicy.breakpointsShort(canonicalCapability()));
  }

  private static PromptCacheCapability canonicalCapability() {
    return PromptCacheCapability.breakpoints(
        EnumSet.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM));
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
