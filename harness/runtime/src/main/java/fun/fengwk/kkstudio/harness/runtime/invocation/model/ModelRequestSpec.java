package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Model invocation 的紧凑冻结请求。
 *
 * <p>{@link ProviderType}、{@link ModelDescriptor}、{@link ModelVariant} 与 preamble / bindings /
 * cacheControl 是本次调用的唯一 durable 契约。完整对话历史、可由 {@code toolBindings} 派生的 Provider tools、顶层
 * Environment、YOLO 与 contextWindow 都不进入本对象；每次 attempt 由 {@link ModelRequestMaterializer} 从
 * EntryPath 重建内存 {@code ProviderRequest}。
 */
public record ModelRequestSpec(
    ProviderType providerType,
    ModelDescriptor model,
    ModelVariant variant,
    List<AgentMessage> preambleMessages,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl,
    CompactionRequest compaction) {

  /** 构造不具备子 Agent 委派能力的请求。 */
  public ModelRequestSpec(
      ProviderType providerType,
      ModelDescriptor model,
      ModelVariant variant,
      List<AgentMessage> preambleMessages,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      ProviderCacheControl cacheControl,
      CompactionRequest compaction) {
    this(
        providerType,
        model,
        variant,
        preambleMessages,
        toolBindings,
        skillBindings,
        List.of(),
        cacheControl,
        compaction);
  }

  public ModelRequestSpec {
    providerType = Objects.requireNonNull(providerType, "providerType");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    preambleMessages = List.copyOf(Objects.requireNonNull(preambleMessages, "preambleMessages"));
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    skillBindings = List.copyOf(Objects.requireNonNull(skillBindings, "skillBindings"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
    requireUniqueToolNames(toolBindings);
    requireUniqueSkillNames(skillBindings);
    requireUniqueSubagentNames(subagentBindings);
    requireConsistentRoutes(toolBindings, skillBindings);
    requireCompactionRequestShape(toolBindings, skillBindings, subagentBindings, compaction);
  }

  private static void requireCompactionRequestShape(
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      List<SubagentBinding> subagentBindings,
      CompactionRequest compaction) {
    if (compaction == null) {
      return;
    }
    if (!toolBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry tool bindings");
    }
    if (!skillBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry skill bindings");
    }
    if (!subagentBindings.isEmpty()) {
      throw new IllegalArgumentException("compaction requests must not carry subagent bindings");
    }
  }

  private static void requireUniqueToolNames(List<ToolBinding> toolBindings) {
    for (int i = 0; i < toolBindings.size(); i++) {
      String name = toolBindings.get(i).descriptor().name();
      for (int j = i + 1; j < toolBindings.size(); j++) {
        if (name.equals(toolBindings.get(j).descriptor().name())) {
          throw new IllegalArgumentException("tool binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireUniqueSkillNames(List<SkillBinding> skillBindings) {
    for (int i = 0; i < skillBindings.size(); i++) {
      String name = skillBindings.get(i).name();
      for (int j = i + 1; j < skillBindings.size(); j++) {
        if (name.equals(skillBindings.get(j).name())) {
          throw new IllegalArgumentException("skill binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireUniqueSubagentNames(List<SubagentBinding> subagentBindings) {
    for (int i = 0; i < subagentBindings.size(); i++) {
      String name = subagentBindings.get(i).name();
      for (int j = i + 1; j < subagentBindings.size(); j++) {
        if (name.equals(subagentBindings.get(j).name())) {
          throw new IllegalArgumentException("subagent binding names must not repeat: " + name);
        }
      }
    }
  }

  private static void requireConsistentRoutes(
      List<ToolBinding> toolBindings, List<SkillBinding> skillBindings) {
    EnvironmentBinding environment = null;
    for (ToolBinding binding : toolBindings) {
      if (binding.type() != ToolType.ENVIRONMENT) {
        continue;
      }
      if (environment == null) {
        environment = binding.environment();
      } else if (!Objects.equals(environment, binding.environment())) {
        throw new IllegalArgumentException("environment-bound tools must share one environment");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironment() == null) {
        continue;
      }
      if (environment == null) {
        environment = skill.sourceEnvironment();
      } else if (!environment.equals(skill.sourceEnvironment())) {
        throw new IllegalArgumentException(
            "skill source environment must match environment-bound tools");
      }
    }
  }
}
