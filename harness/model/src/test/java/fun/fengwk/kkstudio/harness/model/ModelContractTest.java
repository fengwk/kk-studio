package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 模型描述、用量与成本公共契约测试。 */
class ModelContractTest {

  /** 模型输出上限必须位于 context window 内，且输入模态不能缺失。 */
  @Test
  void enforcesModelContextAndInputModalityInvariants() {
    assertThrows(
        IllegalArgumentException.class,
        () -> descriptor(8_192, 8_193, Set.of(ModelInputModality.TEXT)));
    assertThrows(IllegalArgumentException.class, () -> descriptor(8_192, 2_048, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelDescriptor(
                "provider",
                "model",
                "Model",
                8_192,
                2_048,
                Set.of(ModelInputModality.TEXT),
                Set.of(ModelCapability.TEXT),
                List.of(new ModelVariant("long", 4_096, null, null, null, null)),
                pricing()));
  }

  /** 不同 Provider 用量类别必须分别按其适用单价计费。 */
  @Test
  void calculatesCostForEveryUsageCategory() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            new BigDecimal("2"),
            new BigDecimal("4"),
            new BigDecimal("0.5"),
            new BigDecimal("3"),
            new BigDecimal("6"));
    ModelUsage usage = new ModelUsage(800_000, 500_000, 200_000, 100_000, 300_000);

    ModelCost cost = ModelCost.calculate(pricing, usage);

    assertEquals(1_900_000, usage.totalTokens());
    assertEquals("USD", cost.currency());
    assertEquals(new BigDecimal("5.800000000000"), cost.amount());
  }

  /** 任一负用量都会破坏成本计算，必须在公共边界拒绝。 */
  @Test
  void rejectsNegativeUsageCategory() {
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(1, 1, -1, 0, 0));
  }

  /** descriptor 必须防御性复制可变集合，防止配置在运行时被调用方修改。 */
  @Test
  void copiesDescriptorCollections() {
    ModelDescriptor descriptor =
        descriptor(128_000, 8_192, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    assertThrows(
        UnsupportedOperationException.class,
        () -> descriptor.inputModalities().add(ModelInputModality.AUDIO));
    assertThrows(
        UnsupportedOperationException.class,
        () -> descriptor.capabilities().add(ModelCapability.TOOLS));
    assertThrows(
        UnsupportedOperationException.class,
        () -> descriptor.variants().add(new ModelVariant("fast", null, null, null, null, null)));
  }

  private ModelDescriptor descriptor(
      long contextWindow, long maxOutputTokens, Set<ModelInputModality> inputModalities) {
    return new ModelDescriptor(
        "provider",
        "model",
        "Model",
        contextWindow,
        maxOutputTokens,
        inputModalities,
        Set.of(ModelCapability.TEXT),
        List.of(new ModelVariant("default", null, null, null, null, null)),
        pricing());
  }

  private ModelPricing pricing() {
    return new ModelPricing(
        "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }
}
