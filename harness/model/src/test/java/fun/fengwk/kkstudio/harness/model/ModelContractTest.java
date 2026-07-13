package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 模型公共契约的不可变性、参数边界与成本计算测试。 */
class ModelContractTest {

  /** 缓存输入必须不大于总输入，避免产生负的可计费输入 token。 */
  @Test
  void rejectsCachedTokensExceedingInput() {
    assertThrows(IllegalArgumentException.class, () -> new ModelUsage(3, 1, 0, 4));
  }

  /** 成本计算应区分普通输入、缓存输入和输出 token。 */
  @Test
  void calculatesCostFromUsageAndPricing() {
    ModelPricing pricing =
        new ModelPricing("USD", new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("0.5"));

    ModelCost cost = ModelCost.calculate(pricing, new ModelUsage(1_000_000, 500_000, 0, 200_000));

    assertEquals("USD", cost.currency());
    assertEquals(new BigDecimal("3.700000000000"), cost.amount());
  }

  /** descriptor 必须防御性复制可变集合，防止配置在运行时被调用方修改。 */
  @Test
  void copiesDescriptorCollections() {
    ModelDescriptor descriptor =
        new ModelDescriptor(
            "provider",
            "model",
            "Model",
            Set.of(ModelCapability.TEXT),
            List.of(new ModelVariant("default", null, null, null, null, null)),
            new ModelPricing("USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

    assertThrows(
        UnsupportedOperationException.class,
        () -> descriptor.capabilities().add(ModelCapability.TOOLS));
    assertThrows(
        UnsupportedOperationException.class,
        () -> descriptor.variants().add(new ModelVariant("fast", null, null, null, null, null)));
  }

  /** tool role 需要关联调用 ID，而其他 role 不得伪造该关联。 */
  @Test
  void enforcesToolMessageCorrelationInvariant() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderMessage(ProviderMessageRole.TOOL, "ok", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderMessage(ProviderMessageRole.USER, "hello", "call-1"));
  }
}
