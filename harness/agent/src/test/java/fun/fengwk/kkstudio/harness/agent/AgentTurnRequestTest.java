package fun.fengwk.kkstudio.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Agent Turn 输入边界测试。 */
class AgentTurnRequestTest {

  /** Turn 请求只保留一份 ToolDescriptor，Provider 工具声明留给 Engine 在内部转换。 */
  @Test
  void keepsToolDescriptorsAsTheSinglePublicToolDeclaration() {
    AgentTurnRequest request =
        new AgentTurnRequest(
            model(),
            new ModelVariant("default", null, null, null, null, null),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))),
            List.of(tool()));

    assertEquals(1, request.tools().size());
    assertThrows(UnsupportedOperationException.class, () -> request.tools().clear());
  }

  private ModelDescriptor model() {
    return new ModelDescriptor(
        "provider",
        "model",
        "Model",
        8_192,
        1_024,
        Set.of(ModelInputModality.TEXT),
        Set.of(ModelCapability.TEXT),
        List.of(),
        new ModelPricing(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private ToolDescriptor tool() {
    return new ToolDescriptor(
        "echo",
        "1.0.0",
        "Echo text",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        ToolExecutionMode.CLOUD,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }
}
