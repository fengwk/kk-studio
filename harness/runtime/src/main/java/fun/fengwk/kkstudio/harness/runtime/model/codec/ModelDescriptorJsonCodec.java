package fun.fengwk.kkstudio.harness.runtime.model.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 共享的 {@link ModelDescriptor} / {@link ModelVariant} 严格 deterministic JSON codec。
 *
 * <p>本 codec 是 runtime model 包中 model 与 variant 子树的唯一权威实现；其他 codec（如 {@code
 * ProviderRequestJsonCodec}）必须把对应子树委派到本类，避免 wire 漂移。
 *
 * <p>字段访问为逐字段 JsonNode 读 / 写：每个对象层都先列出允许字段集合，未知字段直接抛 {@link IllegalArgumentException}；类型不符（如非
 * string 的 enum 元素、非 boolean 的 flag）同样抛错。底层 Jackson {@link ObjectMapper} 启用 {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS} 与 {@link
 * JsonParser.Feature#STRICT_DUPLICATE_DETECTION}，在边界拒绝 trailing token 与 duplicate field。
 *
 * <p>canonical 输出策略：
 *
 * <ul>
 *   <li>对象字段按声明顺序写入。
 *   <li>enum {@code Set} 字段（{@code inputModalities}）按 enum name 排序，使输出在跨 JVM 时保持 deterministic。
 *   <li>{@link BigDecimal} 字段以 {@code toPlainString()} 文本输出。
 *   <li>{@code ModelVariant} 的 nullable 字段（{@code maxOutputTokens} / {@code temperature} 等）显式输出
 *       {@code null} 而非省略，便于 schema 对照。
 * </ul>
 *
 * <p>durable 形态为直接 V1：descriptor 固定六个字段（含 {@code inputModalities}），旧五字段形态由严格字段校验拒绝，不做 dual-read 兼容。
 */
public final class ModelDescriptorJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** descriptor 字段顺序。 */
  private static final Set<String> DESCRIPTOR_FIELDS =
      orderedSet("providerName", "modelName", "inputModalities", "tools", "reasoning", "pricing");

  /** variant 字段顺序。 */
  private static final Set<String> VARIANT_FIELDS =
      orderedSet(
          "id",
          "maxOutputTokens",
          "temperature",
          "topP",
          "topK",
          "frequencyPenalty",
          "presencePenalty",
          "stopSequences",
          "reasoningEffort");

  private static final Set<String> PRICING_FIELDS =
      orderedSet(
          "currency",
          "pricingTier",
          "serviceTier",
          "serviceTierMultiplier",
          "version",
          "inputPerMillionTokens",
          "outputPerMillionTokens",
          "cacheReadPerMillionTokens",
          "cacheWritePerMillionTokens",
          "cacheWriteLongPerMillionTokens",
          "reasoningPerMillionTokens");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ModelDescriptorJsonCodec() {}

  /** 把单个 {@link ModelDescriptor} 编码为 canonical JSON 文本。 */
  public String encodeDescriptor(ModelDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return write(encodeDescriptorNode(descriptor));
  }

  /** 把单个 {@link ModelDescriptor} 编码为 canonical {@link ObjectNode}。 */
  public ObjectNode encodeDescriptorNode(ModelDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return writeDescriptor(descriptor);
  }

  /** 解码单个 canonical descriptor JSON 文本。 */
  public ModelDescriptor decodeDescriptor(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeDescriptorNode(MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed model descriptor JSON", exception);
    }
  }

  /** 从任意 {@link JsonNode} 解码 descriptor；非对象节点抛 {@link IllegalArgumentException}。 */
  public ModelDescriptor decodeDescriptorNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    return readDescriptor(object(value, "model"));
  }

  /** 把单个 {@link ModelVariant} 编码为 canonical JSON 文本。 */
  public String encodeVariant(ModelVariant variant) {
    Objects.requireNonNull(variant, "variant");
    return write(encodeVariantNode(variant));
  }

  /** 把单个 {@link ModelVariant} 编码为 canonical {@link ObjectNode}。 */
  public ObjectNode encodeVariantNode(ModelVariant variant) {
    Objects.requireNonNull(variant, "variant");
    return writeVariant(variant);
  }

  /** 解码单个 canonical variant JSON 文本。 */
  public ModelVariant decodeVariant(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeVariantNode(MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed model variant JSON", exception);
    }
  }

  /** 从任意 {@link JsonNode} 解码 variant。 */
  public ModelVariant decodeVariantNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    return readVariant(object(value, "variant"));
  }

  // ---------- ModelDescriptor ----------

  private static ObjectNode writeDescriptor(ModelDescriptor descriptor) {
    ObjectNode node = NODES.objectNode();
    node.put("providerName", descriptor.providerName());
    node.put("modelName", descriptor.modelName());
    ArrayNode inputModalities = node.putArray("inputModalities");
    for (ModelInputModality modality :
        sorted(descriptor.inputModalities(), Comparator.comparing(Enum::name))) {
      inputModalities.add(modality.name());
    }
    node.put("tools", descriptor.tools());
    node.put("reasoning", descriptor.reasoning());
    node.set("pricing", writePricing(descriptor.pricing()));
    return node;
  }

  private static ModelDescriptor readDescriptor(ObjectNode node) {
    requireFields(node, DESCRIPTOR_FIELDS, "model");
    String providerName = text(node, "providerName");
    String modelName = text(node, "modelName");
    Set<ModelInputModality> inputModalities = readInputModalities(node);
    boolean tools = bool(node, "tools");
    boolean reasoning = bool(node, "reasoning");
    ModelPricing pricing = readPricing(node.get("pricing"));
    return new ModelDescriptor(providerName, modelName, inputModalities, tools, reasoning, pricing);
  }

  private static Set<ModelInputModality> readInputModalities(ObjectNode node) {
    ArrayNode modalities = array(node.get("inputModalities"), "inputModalities");
    if (modalities.isEmpty()) {
      throw new IllegalArgumentException("inputModalities must not be empty");
    }
    Set<ModelInputModality> result = new LinkedHashSet<>();
    for (JsonNode item : modalities) {
      if (!item.isTextual() || item.textValue().isBlank()) {
        throw new IllegalArgumentException("inputModalities must contain non-blank strings");
      }
      ModelInputModality modality;
      try {
        modality = ModelInputModality.valueOf(item.textValue());
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(
            "unknown input modality: " + item.textValue(), exception);
      }
      if (!result.add(modality)) {
        throw new IllegalArgumentException("inputModalities must not contain duplicates");
      }
    }
    return result;
  }

  // ---------- ModelVariant ----------

  private static ObjectNode writeVariant(ModelVariant variant) {
    ObjectNode node = NODES.objectNode();
    node.put("id", variant.id());
    encodeNullableInt(node, "maxOutputTokens", variant.maxOutputTokens());
    encodeNullableDouble(node, "temperature", variant.temperature());
    encodeNullableDouble(node, "topP", variant.topP());
    encodeNullableInt(node, "topK", variant.topK());
    encodeNullableDouble(node, "frequencyPenalty", variant.frequencyPenalty());
    encodeNullableDouble(node, "presencePenalty", variant.presencePenalty());
    ArrayNode stopSequences = node.putArray("stopSequences");
    for (String stop : variant.stopSequences()) {
      stopSequences.add(stop);
    }
    if (variant.reasoningEffort() == null) {
      node.putNull("reasoningEffort");
    } else {
      node.put("reasoningEffort", variant.reasoningEffort());
    }
    return node;
  }

  private static ModelVariant readVariant(ObjectNode node) {
    requireFields(node, VARIANT_FIELDS, "variant");
    Integer maxOutputTokens = decodeNullableInt(node, "maxOutputTokens");
    Double temperature = decodeNullableDouble(node, "temperature");
    Double topP = decodeNullableDouble(node, "topP");
    Integer topK = decodeNullableInt(node, "topK");
    Double frequencyPenalty = decodeNullableDouble(node, "frequencyPenalty");
    Double presencePenalty = decodeNullableDouble(node, "presencePenalty");
    ArrayNode stopSequences = array(node.get("stopSequences"), "stopSequences");
    List<String> stopList = new ArrayList<>(stopSequences.size());
    for (JsonNode item : stopSequences) {
      if (!item.isTextual() || item.textValue().isBlank()) {
        throw new IllegalArgumentException("stopSequences must contain non-blank strings");
      }
      stopList.add(item.textValue());
    }
    String reasoningEffort = decodeNullableText(node, "reasoningEffort");
    return new ModelVariant(
        text(node, "id"),
        maxOutputTokens,
        temperature,
        topP,
        topK,
        frequencyPenalty,
        presencePenalty,
        stopList,
        reasoningEffort);
  }

  // ---------- ModelPricing ----------

  private static ObjectNode writePricing(ModelPricing pricing) {
    ObjectNode node = NODES.objectNode();
    node.put("currency", pricing.currency());
    node.put("pricingTier", pricing.pricingTier());
    node.put("serviceTier", pricing.serviceTier());
    node.put("serviceTierMultiplier", pricing.serviceTierMultiplier().toPlainString());
    node.put("version", pricing.version());
    node.put("inputPerMillionTokens", pricing.inputPerMillionTokens().toPlainString());
    node.put("outputPerMillionTokens", pricing.outputPerMillionTokens().toPlainString());
    node.put("cacheReadPerMillionTokens", pricing.cacheReadPerMillionTokens().toPlainString());
    node.put("cacheWritePerMillionTokens", pricing.cacheWritePerMillionTokens().toPlainString());
    node.put(
        "cacheWriteLongPerMillionTokens", pricing.cacheWriteLongPerMillionTokens().toPlainString());
    node.put("reasoningPerMillionTokens", pricing.reasoningPerMillionTokens().toPlainString());
    return node;
  }

  private static ModelPricing readPricing(JsonNode value) {
    ObjectNode node = object(value, "pricing");
    requireFields(node, PRICING_FIELDS, "pricing");
    return new ModelPricing(
        text(node, "currency"),
        text(node, "pricingTier"),
        text(node, "serviceTier"),
        decimal(node, "serviceTierMultiplier"),
        text(node, "version"),
        decimal(node, "inputPerMillionTokens"),
        decimal(node, "outputPerMillionTokens"),
        decimal(node, "cacheReadPerMillionTokens"),
        decimal(node, "cacheWritePerMillionTokens"),
        decimal(node, "cacheWriteLongPerMillionTokens"),
        decimal(node, "reasoningPerMillionTokens"));
  }

  // ---------- 共享工具方法 ----------

  /**
   * 暴露给同模块其他 codec（如 {@code ProviderRequestJsonCodec}）的 JsonNode 字段读工具，签名严格遵守
   * ProviderRequestJsonCodec 的现有实现，保证两处行为完全一致。
   */
  static ObjectNode object(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  static ArrayNode array(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
    }
    return array;
  }

  static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  static String decodeNullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text or null");
    }
    return value.textValue();
  }

  static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be boolean");
    }
    return value.booleanValue();
  }

  static void encodeNullableInt(ObjectNode node, String field, Integer value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  static void encodeNullableDouble(ObjectNode node, String field, Double value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  static Integer decodeNullableInt(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be integer or null");
    }
    return value.intValue();
  }

  static Double decodeNullableDouble(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException(field + " must be number or null");
    }
    double parsed = value.doubleValue();
    if (!Double.isFinite(parsed)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
    return parsed;
  }

  static BigDecimal decimal(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    try {
      return new BigDecimal(value.textValue());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(field + " must be a decimal", exception);
    }
  }

  static void requireFields(ObjectNode node, Set<String> expected, String name) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "unexpected fields for " + name + ": " + actual + " (expected " + expected + ")");
    }
  }

  static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return set;
  }

  /** 按给定比较器排序的不可变列表，用于 enum Set 的 deterministic 输出。 */
  static <T> List<T> sorted(Set<T> values, Comparator<? super T> comparator) {
    return values.stream().sorted(comparator).toList();
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot encode model JSON", exception);
    }
  }
}
