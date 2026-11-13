package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.List;
import java.util.Map;

/** Test fixtures for the canonical ModelRequestSpec record constructor. */
public final class ModelRequestSpecTestSupport {

  private ModelRequestSpecTestSupport() {}

  public static ModelRequestSpec spec(
      ProviderType providerType,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings) {
    return spec(
        providerType,
        model,
        variant,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        null);
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ModelGroupExecution groupExecution) {
    return spec(
        providerType,
        model,
        variant,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        groupExecution,
        systemSections(systemInstruction));
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ModelGroupExecution groupExecution,
      Map<String, String> systemSections) {
    return new ModelRequestSpec(
        providerType,
        model,
        variant,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        groupExecution,
        systemSections);
  }

  private static Map<String, String> systemSections(String systemInstruction) {
    return systemInstruction == null ? Map.of() : Map.of("system", systemInstruction);
  }
}
