package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Durable request snapshot for one model attempt.
 *
 * <p>The provider request is the exact transport payload. Tool and skill bindings are persisted
 * beside it so later tool routing and {@code load_skill} execution never re-resolve a changed Agent
 * or Environment.
 */
public record ModelInvocationRequest(
    ProviderRequest providerRequest,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    boolean yoloEnabled) {

  public ModelInvocationRequest {
    providerRequest = Objects.requireNonNull(providerRequest, "providerRequest");
    toolBindings = copyUniqueTools(toolBindings);
    skillBindings = copyUniqueSkills(skillBindings);
    validateProviderTools(providerRequest, toolBindings);
  }

  private static void validateProviderTools(
      ProviderRequest providerRequest, List<ToolBinding> toolBindings) {
    List<ProviderToolDefinition> providerTools = providerRequest.tools();
    if (providerTools.size() != toolBindings.size()) {
      throw new IllegalArgumentException(
          "providerRequest.tools and toolBindings must contain the same number of tools");
    }
    ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();
    Set<String> providerNames = new HashSet<>();
    for (int index = 0; index < toolBindings.size(); index++) {
      ProviderToolDefinition providerTool =
          Objects.requireNonNull(providerTools.get(index), "providerRequest.tools[]");
      if (!providerNames.add(providerTool.name())) {
        throw new IllegalArgumentException(
            "providerRequest.tools contains duplicate name: " + providerTool.name());
      }
      ToolBinding binding = toolBindings.get(index);
      String expectedSchema = descriptorCodec.encodeInputSchema(binding.descriptor().inputSchema());
      if (!binding.descriptor().name().equals(providerTool.name())
          || !binding.descriptor().description().equals(providerTool.description())
          || !expectedSchema.equals(providerTool.inputSchemaJson())) {
        throw new IllegalArgumentException(
            "providerRequest.tools must exactly match toolBindings at index " + index);
      }
    }
  }

  private static List<ToolBinding> copyUniqueTools(List<ToolBinding> source) {
    Objects.requireNonNull(source, "toolBindings");
    Set<String> names = new HashSet<>();
    for (ToolBinding binding : source) {
      Objects.requireNonNull(binding, "toolBindings[]");
      if (!names.add(binding.descriptor().name())) {
        throw new IllegalArgumentException(
            "toolBindings contains duplicate name: " + binding.descriptor().name());
      }
    }
    return List.copyOf(source);
  }

  private static List<SkillBinding> copyUniqueSkills(List<SkillBinding> source) {
    Objects.requireNonNull(source, "skillBindings");
    Set<String> names = new HashSet<>();
    for (SkillBinding skill : source) {
      Objects.requireNonNull(skill, "skillBindings[]");
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException(
            "skillBindings contains duplicate name: " + skill.name());
      }
    }
    return List.copyOf(source);
  }
}
