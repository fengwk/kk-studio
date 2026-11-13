package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test fixtures for the canonical ModelRequestSpec record constructor. */
public final class ModelRequestSpecTestSupport {

  private ModelRequestSpecTestSupport() {}

  public static ModelRequestSpec spec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings) {
    return spec(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        Collections.singletonList(variant),
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        null);
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ModelGroupExecution groupExecution) {
    return spec(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        Collections.singletonList(variant),
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        groupExecution);
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      List<ModelVariant> modelVariants,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings) {
    return spec(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        modelVariants,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        null);
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      List<ModelVariant> modelVariants,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ModelGroupExecution groupExecution) {
    return new ModelRequestSpec(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        modelVariants,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        groupExecution,
        systemSections(systemInstruction));
  }

  public static ModelRequestSpec spec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      List<ModelVariant> modelVariants,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ModelGroupExecution groupExecution,
      Map<String, String> systemSections) {
    return new ModelRequestSpec(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        modelVariants,
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
