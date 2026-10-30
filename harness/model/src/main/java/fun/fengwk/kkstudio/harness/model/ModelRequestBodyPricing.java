package fun.fengwk.kkstudio.harness.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelPricingCondition.RequestBody;
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition.ScalarType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * request-body pricing 的纯判定实现：从当前 {@link ModelPricing} 收集有限条件计划，并在最终编码请求体上计算条件指纹布尔快照。
 *
 * <p>计划最多包含 {@link RequestBodyPricingSnapshot#MAX_CONDITIONS} 个唯一条件；超出部分不参与本次执行，保持未知而不是截断
 * 请求值。请求体只在本方法的一次调用内以临时字节存在，快照绝不保留 body 或字段原值。
 *
 * <p>边界：请求体最大 {@value #MAX_BODY_BYTES} 字节、最大嵌套 {@value #MAX_NESTING_DEPTH}，JSON 必须是单个值且拒绝重复属性名。
 * 任何缺失、非法或超限的 body 都让本次快照为空（全部未知），绝不做前缀解析。
 */
public final class ModelRequestBodyPricing {

  public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
  public static final int MAX_NESTING_DEPTH = 64;

  private static final ObjectMapper BODY_MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BODY_BYTES)
                          .maxNestingDepth(MAX_NESTING_DEPTH)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private ModelRequestBodyPricing() {}

  /** 从当前 pricing 冻结一次 attempt 的条件计划；没有 request-body 条件时返回空计划，不需要解析 body。 */
  public static Plan plan(ModelPricing pricing) {
    Objects.requireNonNull(pricing, "pricing");
    Map<String, RequestBody> conditions = new LinkedHashMap<>();
    boolean[] hasConditions = {false};
    for (ModelPricing.Override override : pricing.overrides()) {
      collect(override.when(), conditions, hasConditions);
    }
    for (ModelPricing.Multiplier multiplier : pricing.multipliers()) {
      collect(multiplier.when(), conditions, hasConditions);
    }
    return new Plan(List.copyOf(conditions.values()), hasConditions[0]);
  }

  /** 条件指纹：SHA-256(UTF8(path + NUL + type + NUL + canonicalExpectedValue))，小写十六进制。 */
  public static String fingerprint(RequestBody condition) {
    Objects.requireNonNull(condition, "condition");
    String material =
        condition.path() + '\0' + condition.type().wireName() + '\0' + condition.canonicalValue();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        hex.append(Character.forDigit((value >> 4) & 0xF, 16));
        hex.append(Character.forDigit(value & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static void collect(
      ModelPricingCondition condition,
      Map<String, RequestBody> conditions,
      boolean[] hasConditions) {
    if (condition.requestBody() != null) {
      hasConditions[0] = true;
      if (conditions.size() < RequestBodyPricingSnapshot.MAX_CONDITIONS) {
        conditions.putIfAbsent(fingerprint(condition.requestBody()), condition.requestBody());
      }
    }
    if (condition.all() != null) {
      for (ModelPricingCondition nested : condition.all()) {
        collect(nested, conditions, hasConditions);
      }
    }
    if (condition.any() != null) {
      for (ModelPricingCondition nested : condition.any()) {
        collect(nested, conditions, hasConditions);
      }
    }
    if (condition.not() != null) {
      collect(condition.not(), conditions, hasConditions);
    }
  }

  /** 一次 attempt 冻结的计划；不可变，可安全地只在本 attempt 内使用。 */
  public static final class Plan {

    private final List<RequestBody> conditions;
    private final boolean hasConditions;

    private Plan(List<RequestBody> conditions, boolean hasConditions) {
      this.conditions = List.copyOf(conditions);
      this.hasConditions = hasConditions;
    }

    /** 配置中是否存在任意 request-body 条件（包括因数量上限未进入计划的叶子）。 */
    public boolean hasConditions() {
      return hasConditions;
    }

    /**
     * 在最终编码请求体上计算快照；没有条件时返回 {@link RequestBodyPricingSnapshot#EMPTY}，不需要解析 body。
     *
     * <p>body 为 null、超限或非法 JSON 时返回空快照，所有 request-body 条件保持未知。
     */
    public RequestBodyPricingSnapshot evaluate(byte[] body) {
      if (conditions.isEmpty()) {
        return RequestBodyPricingSnapshot.EMPTY;
      }
      if (body == null || body.length > MAX_BODY_BYTES) {
        return RequestBodyPricingSnapshot.EMPTY;
      }
      JsonNode root;
      try {
        root = BODY_MAPPER.readTree(body);
      } catch (JacksonException | IllegalArgumentException error) {
        return RequestBodyPricingSnapshot.EMPTY;
      }
      if (root == null || root.isMissingNode()) {
        return RequestBodyPricingSnapshot.EMPTY;
      }
      Map<String, Boolean> matches = new LinkedHashMap<>();
      for (RequestBody condition : conditions) {
        matches.put(fingerprint(condition), matches(condition, root));
      }
      return new RequestBodyPricingSnapshot(matches);
    }
  }

  private static boolean matches(RequestBody condition, JsonNode root) {
    JsonNode current = root;
    for (String token : condition.pathSegments()) {
      if (current.isObject()) {
        current = current.get(token);
      } else if (current.isArray()) {
        int index = canonicalArrayIndex(token);
        if (index < 0 || index >= current.size()) {
          return false;
        }
        current = current.get(index);
      } else {
        return false;
      }
      if (current == null || current.isMissingNode()) {
        return false;
      }
    }
    return switch (condition.type()) {
      case NULL -> current.isNull();
      case STRING -> current.isString() && current.stringValue().equals(condition.canonicalValue());
      case BOOLEAN -> current.isBoolean()
          && current.booleanValue() == Boolean.parseBoolean(condition.canonicalValue());
      case NUMBER -> current.isNumber()
          && current.decimalValue().compareTo(new BigDecimal(condition.canonicalValue())) == 0;
    };
  }

  private static int canonicalArrayIndex(String token) {
    if (token.isEmpty() || token.length() > 10) {
      return -1;
    }
    if (token.length() > 1 && token.charAt(0) == '0') {
      return -1;
    }
    for (int index = 0; index < token.length(); index++) {
      char current = token.charAt(index);
      if (current < '0' || current > '9') {
        return -1;
      }
    }
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  /** 供 UI/文档使用的类型名集合，顺序稳定。 */
  public static List<String> scalarTypeNames() {
    List<String> names = new ArrayList<>(ScalarType.values().length);
    for (ScalarType type : ScalarType.values()) {
      names.add(type.wireName());
    }
    return List.copyOf(names);
  }
}
