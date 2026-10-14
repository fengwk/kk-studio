package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;

import java.math.BigDecimal;

/**
 * {@link ProviderProtocolOptionsJson} 契约：空选项返回空 object、数值按 JSON 语义还原，且每次调用都返回互不影响的独立副本。
 *
 * <p>协议编码器会把返回值直接合并进请求体并任意修改，因此「副本独立」与「数值原样」是后续四个协议编码器无损合并的前提。
 */
class ProviderProtocolOptionsJsonTest {

  /** 未声明选项的 variant 得到空 object，可直接作为合并基座。 */
  @Test
  void returnsEmptyObjectWhenVariantDeclaresNoOptions() {
    ObjectNode options = ProviderProtocolOptionsJson.copyOfOptions(new ModelVariant("default"));

    assertTrue(options.isEmpty());
    assertEquals("{}", options.toString());
  }

  /** 嵌套 object/array 与厂商原生数值必须原样还原：整数不带引号，小数不丢精度。 */
  @Test
  void restoresNestedStructureAndNativeNumericSemantics() {
    ModelVariant variant =
        new ModelVariant(
            "balanced",
            "medium",
            new ProviderProtocolOptions(
                "{\"metadata\":{\"source\":\"catalog\"},\"thinking\":{\"budget_tokens\":4096},"
                    + "\"ratio\":1.10,\"revision\":1758880000000,\"flags\":[true,false]}"));

    ObjectNode options = ProviderProtocolOptionsJson.copyOfOptions(variant);

    assertEquals("catalog", options.path("metadata").path("source").asString(""));
    assertEquals(4096, options.path("thinking").path("budget_tokens").asInt(0));
    assertTrue(options.path("ratio").isNumber());
    assertEquals("1.1", options.path("ratio").asString(""));
    assertTrue(options.path("revision").isIntegralNumber());
    assertEquals(1758880000000L, options.path("revision").asLong(0L));
    assertEquals(2, options.path("flags").size());
  }

  /**
   * 厂商原生数值按 BigDecimal 还原：{@code 1e309}/{@code 1e-324} 不得退化为 {@code Infinity}/{@code 0}，高精度小数不得截断；
   * canonical 文本按既有语义移除无语义尾零（{@code 1.10 -> 1.1}）。
   */
  @Test
  void restoresHighPrecisionNativeNumbersWithoutFloatLoss() {
    ModelVariant variant =
        new ModelVariant(
            "precise",
            null,
            new ProviderProtocolOptions(
                "{\"big\":1e309,\"tiny\":1e-324,"
                    + "\"exact\":0.1234567890123456789012345678,\"ratio\":1.10}"));

    ObjectNode options = ProviderProtocolOptionsJson.copyOfOptions(variant);

    assertEquals(new BigDecimal("1e309"), options.path("big").decimalValue());
    assertEquals(new BigDecimal("1e-324"), options.path("tiny").decimalValue());
    assertEquals(
        new BigDecimal("0.1234567890123456789012345678"), options.path("exact").decimalValue());
    // canonical 语义：无语义尾零被移除，1.10 归一为 1.1
    assertEquals(new BigDecimal("1.1"), options.path("ratio").decimalValue());
  }

  /** 防御性副本：每次调用返回新树，编码器对副本的修改不会影响 variant 或在后续调用中可见。 */
  @Test
  void returnsIndependentCopyOnEveryCall() {
    ModelVariant variant =
        new ModelVariant(
            "balanced",
            null,
            new ProviderProtocolOptions("{\"metadata\":{\"source\":\"catalog\"}}"));

    ObjectNode first = ProviderProtocolOptionsJson.copyOfOptions(variant);
    ObjectNode second = ProviderProtocolOptionsJson.copyOfOptions(variant);
    assertNotSame(first, second);

    ((ObjectNode) first.path("metadata")).put("source", "mutated");
    first.put("injected", true);

    assertEquals("catalog", second.path("metadata").path("source").asString(""));
    assertTrue(second.path("injected").isMissingNode());
    assertEquals(
        "catalog",
        ProviderProtocolOptionsJson.copyOfOptions(variant)
            .path("metadata")
            .path("source")
            .asString(""));
  }

  /** variant 不可为 null：编码器传入错误对象时必须显式失败，而不是产出空选项。 */
  @Test
  void rejectsNullVariant() {
    assertThrows(NullPointerException.class, () -> ProviderProtocolOptionsJson.copyOfOptions(null));
  }
}
