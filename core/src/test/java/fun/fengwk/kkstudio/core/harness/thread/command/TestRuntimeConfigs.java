package fun.fengwk.kkstudio.core.harness.thread.command;

import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;

/** Minimal frozen RuntimeConfigSnapshot fixtures for final-schema integration tests. */
public final class TestRuntimeConfigs {
  private TestRuntimeConfigs() {}

  public static RuntimeConfigSnapshot bootstrap() {
    return config("bootstrap", false);
  }

  public static RuntimeConfigSnapshot config(String name, boolean yolo) {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            1,
            2,
            ProviderType.OPENAI,
            "model",
            true,
            false,
            new ModelPricing(
                "USD",
                "default",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(1, name, "prompt"),
        new ModelSnapshot(descriptor, variant),
        List.of(),
        List.of(),
        yolo);
  }
}
