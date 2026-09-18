package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次 Model invocation 的紧凑冻结请求。
 *
 * <p>{@link ProviderType}、{@code providerConnectionGenerationId}、{@link ModelDescriptor}、{@link
 * ModelVariant}、{@code outputTokens} 与 preamble / bindings / cacheControl 是本次调用的唯一 durable
 * 契约。connection generation 防止 Provider endpoint、凭据或协议配置轮换后，既有 invocation 静默改用新连接。{@code
 * outputTokens} 是本次请求唯一的输出预算（普通 turn 由 Model 级 {@code limit.output} 与剩余上下文计算，压缩 turn
 * 由压缩阶段预算决定），Provider 请求必须原样携带该预算。完整对话历史、可由 {@code toolBindings} 派生的 Provider tools、顶层
 * Environment、YOLO、contextWindow 与压缩元数据（压缩调用由 basis EntryPath 末尾 owned TURN_START.compaction
 * 识别）都不进入本对象；每次 attempt 由 {@link ModelRequestMaterializer} 从 EntryPath 重建内存 {@code
 * ProviderRequest}。
 */
public record ModelRequestSpec(
    ProviderType providerType,
    UUID providerConnectionGenerationId,
    ModelDescriptor model,
    ModelVariant variant,
    int outputTokens,
    List<AgentMessage> preambleMessages,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl) {

  /** 构造不具备 Subagent 委派能力的请求。 */
  public ModelRequestSpec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      List<AgentMessage> preambleMessages,
      List<ToolBinding> toolBindings,
      List<SkillBinding> skillBindings,
      ProviderCacheControl cacheControl) {
    this(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        outputTokens,
        preambleMessages,
        toolBindings,
        skillBindings,
        List.of(),
        cacheControl);
  }

  public ModelRequestSpec {
    providerType = Objects.requireNonNull(providerType, "providerType");
    providerConnectionGenerationId =
        Objects.requireNonNull(providerConnectionGenerationId, "providerConnectionGenerationId");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    if (outputTokens <= 0) {
      throw new IllegalArgumentException("outputTokens must be positive");
    }
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
    for (ToolBinding binding : toolBindings) {
      Objects.requireNonNull(binding, "toolBindings[]");
      String name = binding.descriptor().name();
      if (!names.add(name)) {
        throw new IllegalArgumentException("tool binding names must not repeat: " + name);
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
    EnvironmentId environmentId = null;
    boolean environmentSeen = false;
    for (ToolBinding binding : toolBindings) {
      if (!binding.environmentRequired()) {
        continue;
      }
      if (!environmentSeen) {
        environmentId = binding.environmentId();
        environmentSeen = true;
      } else if (!Objects.equals(environmentId, binding.environmentId())) {
        throw new IllegalArgumentException("environment-bound tools must share one environment");
      }
    }
    for (SkillBinding skill : skillBindings) {
      if (skill.sourceEnvironmentId() == null) {
        continue;
      }
      if (!environmentSeen) {
        environmentId = skill.sourceEnvironmentId();
        environmentSeen = true;
      } else if (!Objects.equals(environmentId, skill.sourceEnvironmentId())) {
        throw new IllegalArgumentException(
            "skill source environment must match environment-bound tools");
      }
    }
  }
}
