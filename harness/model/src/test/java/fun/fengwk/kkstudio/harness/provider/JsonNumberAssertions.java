package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * 跨协议自由 JSON 数值保真断言。
 *
 * <p>断言直接比较数值类型与 {@link BigDecimal} 值，而不是把大数转成 String 再比较渲染形态。
 */
public final class JsonNumberAssertions {

  private JsonNumberAssertions() {}

  /** 断言节点是 JSON 数值且数值等于给定字面量（忽略 scale，只校验数值本身）。 */
  public static void assertExactNumber(JsonNode node, String literal) {
    assertTrue(node.isNumber(), () -> "expected JSON number, got: " + node);
    assertEquals(
        0,
        node.decimalValue().compareTo(new BigDecimal(literal)),
        () -> "unexpected number value: " + literal);
  }

  /** 断言数值保留原始尾零：scale 与字面量一致（自由 payload 不得被规范化改写）。 */
  public static void assertPreservedScale(JsonNode node, String literal) {
    assertEquals(
        new BigDecimal(literal).scale(),
        node.decimalValue().scale(),
        () -> "trailing zeros must be preserved verbatim: " + literal);
  }
}
