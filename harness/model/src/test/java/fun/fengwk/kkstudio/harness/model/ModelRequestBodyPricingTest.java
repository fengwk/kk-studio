package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelPricingCondition.RequestBody;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** request-body 指纹、JSON Pointer 解析、typed scalar 相等与未知事实语义。 */
class ModelRequestBodyPricingTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final ModelUsage ONE_MILLION_INPUT =
      new ModelUsage(1_000_000, 0, 0, 0, Map.of(), null, null);

  /** 已 Review 的共享 VECTORS：typed 相等、转义、数组、null 与非标量。 */
  @Test
  void evaluatesReviewedVectors() {
    assertVector(RequestBody.stringValue("/speed", "fast"), "{\"speed\":\"fast\"}", true);
    assertVector(RequestBody.stringValue("/speed", "fast"), "{\"speed\":\"FAST\"}", false);
    assertVector(RequestBody.nullValue("/speed"), "{}", false);
    assertVector(RequestBody.nullValue("/speed"), "{\"speed\":null}", true);
    assertVector(
        RequestBody.booleanValue("/a~1b/~0mode", true), "{\"a/b\":{\"~mode\":true}}", true);
    assertVector(
        RequestBody.stringValue("/options/0/mode", "x"), "{\"options\":[{\"mode\":\"x\"}]}", true);
    assertVector(RequestBody.numberValue("/n", "2.0"), "{\"n\":2}", true);
    assertVector(RequestBody.numberValue("/n", "2"), "{\"n\":\"2\"}", false);
    assertVector(RequestBody.booleanValue("/", false), "{\"\":false}", true);
    assertVector(RequestBody.stringValue("/n", "x"), "{\"n\":{}}", false);
  }

  /** 真实 Jackson3 body 解析下，长小数与大整数的 actual 值必须精确比较且邻值不相等。 */
  @Test
  void comparesActualLongDecimalAndBigIntegerExactly() {
    RequestBody decimal = RequestBody.numberValue("/n", "0.123456789012345678");
    assertVector(decimal, "{\"n\":0.123456789012345678}", true);
    assertVector(decimal, "{\"n\":0.123456789012345679}", false);
    assertVector(decimal, "{\"n\":0.1234567890123456780}", true);

    RequestBody bigInteger = RequestBody.numberValue("/n", "9007199254740993");
    assertVector(bigInteger, "{\"n\":9007199254740993}", true);
    assertVector(bigInteger, "{\"n\":9007199254740992}", false);
    assertVector(bigInteger, "{\"n\":9007199254740994}", false);
  }

  /** body 缺失、超过 32MiB 或嵌套超限都保持 unknown；深度 64 仍可正常求值。 */
  @Test
  void enforcesBodySizeAndNestingBounds() {
    RequestBody condition = RequestBody.stringValue("/a", "x");
    ModelRequestBodyPricing.Plan plan =
        ModelRequestBodyPricing.plan(pricing("bounds", condition, "9"));

    assertTrue(plan.evaluate(null).isEmpty());
    assertTrue(plan.evaluate(new byte[0]).isEmpty());
    assertTrue(plan.evaluate(new byte[ModelRequestBodyPricing.MAX_BODY_BYTES + 1]).isEmpty());
    assertFalse(
        plan.evaluate(nestedBody(ModelRequestBodyPricing.MAX_NESTING_DEPTH)).matches().isEmpty());
    assertTrue(plan.evaluate(nestedBody(ModelRequestBodyPricing.MAX_NESTING_DEPTH + 1)).isEmpty());
  }

  /** 数组只在 canonical 十进制下标上求值；对象属性名 "01" 仍是独立 key，不发生数组键转换。 */
  @Test
  void arrayIndicesAreCanonicalButObjectKeysRemainLiteral() {
    RequestBody leadingZero = RequestBody.stringValue("/a/01", "x");
    assertVector(leadingZero, "{\"a\":[\"x\"]}", false);
    assertVector(leadingZero, "{\"a\":{\"01\":\"x\"}}", true);

    for (String token : List.of("-1", "+1", "1.0", "", "12345678901", "9999999999")) {
      assertVector(RequestBody.stringValue("/a/" + token, "x"), "{\"a\":[\"x\"]}", false);
    }

    RequestBody empty = RequestBody.stringValue("/a/", "x");
    assertVector(empty, "{\"a\":{\"\":\"x\"}}", true);
    assertVector(empty, "{\"a\":[\"x\"]}", false);
  }

  /** 快照只接受合法指纹与布尔值，map 不可变，且字符串表示不泄露条件原值。 */
  @Test
  void snapshotRejectsInvalidDataAndIsImmutable() {
    RequestBody condition = RequestBody.stringValue("/secret", "raw-value");
    String fingerprint = ModelRequestBodyPricing.fingerprint(condition);
    RequestBodyPricingSnapshot snapshot = new RequestBodyPricingSnapshot(Map.of(fingerprint, true));

    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestBodyPricingSnapshot(Map.of("not-a-fingerprint", true)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestBodyPricingSnapshot(Map.of("A".repeat(64), true)));
    Map<String, Boolean> nullValue = new HashMap<>();
    nullValue.put(fingerprint, null);
    assertThrows(IllegalArgumentException.class, () -> new RequestBodyPricingSnapshot(nullValue));
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.matches().put(fingerprint, false));
    assertEquals(new HashMap<>(Map.of(fingerprint, true)), snapshot.matches());
    assertFalse(snapshot.toString().contains("secret"));
    assertFalse(snapshot.toString().contains("raw-value"));
  }

  /** 指纹只由 path/type/规范值决定；rule ID、价格与重复规则不改变身份。 */
  @Test
  void fingerprintIgnoresRuleIdentityAndRates() {
    RequestBody condition = RequestBody.numberValue("/n", "2.0");
    String fingerprint = ModelRequestBodyPricing.fingerprint(condition);
    assertEquals(64, fingerprint.length());
    assertTrue(fingerprint.matches("[0-9a-f]{64}"));

    ModelPricing first = pricing("rule-a", condition, "1");
    ModelPricing second = pricing("rule-b", condition, "9");
    ModelRequestBodyPricing.Plan firstPlan = ModelRequestBodyPricing.plan(first);
    ModelRequestBodyPricing.Plan secondPlan = ModelRequestBodyPricing.plan(second);
    RequestBodyPricingSnapshot firstSnapshot =
        firstPlan.evaluate("{\"n\":2.000}".getBytes(StandardCharsets.UTF_8));
    RequestBodyPricingSnapshot secondSnapshot =
        secondPlan.evaluate("{\"n\":2}".getBytes(StandardCharsets.UTF_8));
    assertEquals(firstSnapshot.matches(), secondSnapshot.matches());
    assertEquals(Map.of(fingerprint, true), firstSnapshot.matches());
  }

  /** 缺失/非法/超限 body 让所有条件保持未知；已知 false 与 unknown 在 all/any/not 中仍按三态传播。 */
  @Test
  void invalidBodyAndUnknownConditionsStayUnknown() {
    RequestBody condition = RequestBody.stringValue("/speed", "fast");
    ModelPricing pricing = pricing("rule", condition, "9");
    ModelRequestBodyPricing.Plan plan = ModelRequestBodyPricing.plan(pricing);

    assertTrue(
        plan.evaluate("{\"speed\":\"fast\",\"speed\":\"fast\"}".getBytes(StandardCharsets.UTF_8))
            .isEmpty());
    assertTrue(
        plan.evaluate("{\"speed\":\"fast\"} trailing".getBytes(StandardCharsets.UTF_8)).isEmpty());
    assertTrue(plan.evaluate("not json".getBytes(StandardCharsets.UTF_8)).isEmpty());
    assertTrue(
        plan.evaluate("{\"speed\":\"slow\"}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .containsValue(false));

    // 没有快照 / 空快照时 request-body 叶子未知；已知 false 的 any 不能把 unknown 折叠成 false。
    assertNull(ModelCost.calculate(pricing, ONE_MILLION_INPUT, NOW, null));
    RequestBody other = RequestBody.stringValue("/x", "y");
    ModelPricingCondition unknownAny =
        new ModelPricingCondition(
            null,
            null,
            null,
            null,
            null,
            List.of(
                new ModelPricingCondition(null, null, null, null, condition, null, null, null),
                new ModelPricingCondition(null, null, null, null, other, null, null, null)),
            null);
    // 已知 false + 缺少其它条件结果 => any 仍未知，整个费用不可定价。
    assertNull(
        ModelCost.calculate(
            pricing("any", unknownAny, "9"),
            ONE_MILLION_INPUT,
            NOW,
            null,
            new RequestBodyPricingSnapshot(
                Map.of(ModelRequestBodyPricing.fingerprint(condition), false))));
    assertEquals(
        new BigDecimal("9"),
        ModelCost.calculate(
                pricing("any", unknownAny, "9"),
                ONE_MILLION_INPUT,
                NOW,
                null,
                new RequestBodyPricingSnapshot(
                    Map.of(
                        ModelRequestBodyPricing.fingerprint(condition),
                        false,
                        ModelRequestBodyPricing.fingerprint(other),
                        true)))
            .input());

    ModelPricingCondition notUnknown =
        new ModelPricingCondition(
            null,
            null,
            null,
            null,
            null,
            null,
            new ModelPricingCondition(null, null, null, null, condition, null, null, null));
    assertNull(ModelCost.calculate(pricing("not", notUnknown, "9"), ONE_MILLION_INPUT, NOW, null));
  }

  /** 数组只接受 canonical 非负十进制下标；非规范下标按路径缺失处理为 false。 */
  @Test
  void arraysUseCanonicalNonNegativeIndices() {
    RequestBody condition = RequestBody.stringValue("/a/1", "x");
    ModelRequestBodyPricing.Plan plan =
        ModelRequestBodyPricing.plan(pricing("array", condition, "9"));
    assertFalse(
        plan.evaluate("{\"a\":[0,1]}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .get(ModelRequestBodyPricing.fingerprint(condition)));
    assertTrue(
        plan.evaluate("{\"a\":[0,\"x\"]}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .containsValue(true));
    assertTrue(
        plan.evaluate("{\"a\":[0,\"x\"]}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .containsValue(true));

    RequestBody leadingZero = RequestBody.stringValue("/a/01", "x");
    assertFalse(
        ModelRequestBodyPricing.plan(pricing("array", leadingZero, "9"))
            .evaluate("{\"a\":[0,1]}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .get(ModelRequestBodyPricing.fingerprint(leadingZero)));
  }

  /** Path/equals 类型与边界在构造时严格校验。 */
  @Test
  void rejectsInvalidPathAndEqualsBounds() {
    assertThrows(IllegalArgumentException.class, () -> RequestBody.stringValue("", "x"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.stringValue("speed", "x"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.stringValue("/a~2b", "x"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.stringValue("/a~", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> RequestBody.stringValue("/" + "a".repeat(1025), "x"));
    assertThrows(
        IllegalArgumentException.class, () -> RequestBody.stringValue("/" + "a".repeat(513), "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RequestBody.stringValue("/" + "/".repeat(17) + "a", "x"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.numberValue("/n", "1e19"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.numberValue("/n", "1e-19"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.numberValue("/n", "01"));
    assertThrows(IllegalArgumentException.class, () -> RequestBody.numberValue("/n", "NaN"));
    assertThrows(
        IllegalArgumentException.class, () -> RequestBody.numberValue("/n", "1".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestBody("/b", ModelPricingCondition.ScalarType.BOOLEAN, "TRUE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestBody("/n", ModelPricingCondition.ScalarType.NULL, "x"));
  }

  /** null 只匹配存在的 null；非标量、标量前缀与缺少 token 都返回 false。 */
  @Test
  void missingAndNonScalarValuesAreFalse() {
    ModelRequestBodyPricing.Plan nullPlan =
        ModelRequestBodyPricing.plan(pricing("null", RequestBody.nullValue("/n"), "9"));
    Map<String, Boolean> matches =
        nullPlan.evaluate("{\"n\":null,\"m\":{}}".getBytes(StandardCharsets.UTF_8)).matches();
    assertEquals(
        Boolean.TRUE,
        matches.get(ModelRequestBodyPricing.fingerprint(RequestBody.nullValue("/n"))));

    RequestBody nested = RequestBody.stringValue("/n/m", "x");
    assertFalse(
        ModelRequestBodyPricing.plan(pricing("nested", nested, "9"))
            .evaluate("{\"n\":1}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .get(ModelRequestBodyPricing.fingerprint(nested)));
    assertFalse(
        ModelRequestBodyPricing.plan(pricing("nested", nested, "9"))
            .evaluate("{\"n\":{}}".getBytes(StandardCharsets.UTF_8))
            .matches()
            .get(ModelRequestBodyPricing.fingerprint(nested)));
  }

  /** 没有条件时不解析 invalid body；条件上限之外的条件不参与本次快照。 */
  @Test
  void emptyPlanSkipsParsingAndConditionLimitOmitsExcess() {
    assertFalse(ModelRequestBodyPricing.plan(ModelPricing.ZERO).hasConditions());
    assertTrue(
        ModelRequestBodyPricing.plan(ModelPricing.ZERO)
            .evaluate("not json".getBytes(StandardCharsets.UTF_8))
            .isEmpty());

    List<ModelPricingCondition> groups = new ArrayList<>();
    int nextValue = 0;
    while (nextValue < RequestBodyPricingSnapshot.MAX_CONDITIONS + 1) {
      List<ModelPricingCondition> leaves = new ArrayList<>();
      for (int offset = 0;
          offset < 43 && nextValue < RequestBodyPricingSnapshot.MAX_CONDITIONS + 1;
          offset++, nextValue++) {
        leaves.add(
            new ModelPricingCondition(
                null,
                null,
                null,
                null,
                RequestBody.numberValue("/n", Integer.toString(nextValue)),
                null,
                null,
                null));
      }
      groups.add(new ModelPricingCondition(null, null, null, null, null, leaves, null, null));
    }
    ModelPricing oversized =
        pricing(
            "all",
            new ModelPricingCondition(null, null, null, null, null, groups, null, null),
            "9");
    ModelRequestBodyPricing.Plan plan = ModelRequestBodyPricing.plan(oversized);
    assertTrue(plan.hasConditions());
    assertEquals(
        RequestBodyPricingSnapshot.MAX_CONDITIONS,
        plan.evaluate("{\"n\":0}".getBytes(StandardCharsets.UTF_8)).matches().size());
  }

  private static byte[] nestedBody(int depth) {
    StringBuilder json = new StringBuilder();
    for (int index = 0; index < depth; index++) {
      json.append("{\"a\":");
    }
    json.append('1');
    for (int index = 0; index < depth; index++) {
      json.append('}');
    }
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void assertVector(RequestBody condition, String body, boolean expected) {
    ModelRequestBodyPricing.Plan plan =
        ModelRequestBodyPricing.plan(pricing("vector", condition, "9"));
    RequestBodyPricingSnapshot snapshot = plan.evaluate(body.getBytes(StandardCharsets.UTF_8));
    Boolean actual = snapshot.matches().get(ModelRequestBodyPricing.fingerprint(condition));
    assertNotNull(actual, body);
    assertEquals(expected, actual, body);
  }

  private static ModelPricing pricing(String id, RequestBody condition, String inputRate) {
    ModelPricingCondition when =
        new ModelPricingCondition(null, null, null, null, condition, null, null, null);
    return pricing(id, when, inputRate);
  }

  private static ModelPricing pricing(
      String id, ModelPricingCondition condition, String inputRate) {
    return new ModelPricing(
        new ModelPricing.RateSet(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null),
        List.of(
            new ModelPricing.Override(
                id,
                condition,
                new ModelPricing.RatePatch(
                    new BigDecimal(inputRate), null, null, null, null, null, false))),
        List.of());
  }
}
