package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 Model invocation 的紧凑冻结请求。
 *
 * <p>{@link ProviderType}、{@link ModelDescriptor}、{@link ModelVariant} 与 preamble / bindings /
 * cacheControl 是本次调用的唯一 durable 契约。完整对话历史、可由 {@code toolBindings} 派生的 Provider tools、顶层
 * Environment、YOLO、contextWindow 与压缩元数据（压缩调用由 basis EntryPath 末尾 owned TURN_START.compaction
 * 识别）都不进入本对象；每次 attempt 由 {@link ModelRequestMaterializer} 从 EntryPath 重建内存 {@code
 * ProviderRequest}。
 */
public record ModelRequestSpec(
    ProviderType providerType,
    ModelDescriptor model,
    ModelVariant variant,
    List<AgentMessage> preambleMessages,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl) {

  /** 构造不具备子 Agent 委派能力的请求。 */
  public ModelRequestSpec(
      ProviderType providerType,
      ModelDescriptor model,
      ModelVariant variant,
      List<AgentMessage> preambleMessages,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      ProviderCacheControl cacheControl) {
    this(
        providerType,
        model,
        variant,
        preambleMessages,
        toolBindings,
        skillBindings,
        List.of(),
        cacheControl);
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
    requireUniqueToolBindings(toolBindings);
    requireUniqueSkillNames(skillBindings);
    requireUniqueSubagentNames(subagentBindings);
    requireConsistentEnvironments(toolBindings, skillBindings);
  }

  private static void requireUniqueToolBindings(List<ToolBinding> toolBindings) {
    Set<String> names = new HashSet<>();
    Set<AgentToolId> ids = new HashSet<>();
    for (ToolBinding binding : toolBindings) {
      Objects.requireNonNull(binding, "toolBindings[]");
      String name = binding.descriptor().name();
      if (!names.add(name)) {
        throw new IllegalArgumentException("tool binding names must not repeat: " + name);
      }
      AgentToolId id = binding.definition().id();
      if (!ids.add(id)) {
        throw new IllegalArgumentException("tool binding ids must not repeat: " + id.value());
      }
    }
  }

  private static void requireUniqueSkillNames(List<SkillBinding> skillBindings) {
    Set<String> names = new HashSet<>();
    for (SkillBinding skill : skillBindings) {
      Objects.requireNonNull(skill, "skillBindings[]");
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("skill binding names must not repeat: " + skill.name());
      }
    }
  }

  private static void requireUniqueSubagentNames(List<SubagentBinding> subagentBindings) {
    Set<String> names = new HashSet<>();
    for (SubagentBinding subagent : subagentBindings) {
      Objects.requireNonNull(subagent, "subagentBindings[]");
      if (!names.add(subagent.name())) {
        throw new IllegalArgumentException(
            "subagent binding names must not repeat: " + subagent.name());
      }
    }
  }

  private static void requireConsistentEnvironments(
      List<ToolBinding> toolBindings, List<SkillBinding> skillBindings) {
    EnvironmentBinding environment = null;
    boolean environmentSeen = false;
    for (ToolBinding binding : toolBindings) {
      if (!binding.environmentRequired()) {
        continue;
      }
      if (!environmentSeen) {
        environment = binding.environment();
        environmentSeen = true;
      } else if (!Objects.equals(environment, binding.environment())) {
        throw new IllegalArgumentException("environment-bound tools must share one environment");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironment() == null) {
        continue;
      }
      if (!environmentSeen) {
        environment = skill.sourceEnvironment();
        environmentSeen = true;
      } else if (!Objects.equals(environment, skill.sourceEnvironment())) {
        throw new IllegalArgumentException(
            "skill source environment must match environment-bound tools");
      }
    }
  }
}
