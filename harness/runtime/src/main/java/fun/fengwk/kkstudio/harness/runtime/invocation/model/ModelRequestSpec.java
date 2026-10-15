package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次 Model invocation 的紧凑冻结请求。
 *
 * <p>{@link ProviderType}、{@code providerConnectionGenerationId}、{@link ModelDescriptor}、{@link
 * ModelVariant}、{@code outputTokens} 与唯一的 {@code systemInstruction} / bindings / cacheControl
 * 是本次调用的唯一 durable 契约。connection generation 防止 Provider endpoint、凭据或协议配置轮换后，既有 invocation
 * 静默改用新连接。{@code outputTokens} 是本次请求唯一的输出预算（普通 turn 由 Model 级 {@code limit.output} 与剩余上下文计算，压缩 turn
 * 由压缩阶段预算决定），Provider 请求必须原样携带该预算。完整对话历史、可由 {@code toolBindings} 派生的 Provider tools、顶层
 * Environment、YOLO、contextWindow 与压缩元数据（压缩调用由 basis EntryPath 末尾 owned TURN_START.compaction
 * 识别）都不进入本对象；每次 attempt 由 {@link ModelRequestMaterializer} 从 EntryPath 重建内存 {@code
 * ProviderRequest}。
 *
 * <p>{@code catalogEpoch} 是本请求冻结工具面所租用的目录 epoch，即使 {@code toolBindings} 为空也必须冻结；所有 binding 必须 与它同
 * epoch，执行或历史恢复都只能按该 epoch 取回同一份冻结目录。
 */
public record ModelRequestSpec(
    long catalogEpoch,
    ProviderType providerType,
    UUID providerConnectionGenerationId,
    ModelDescriptor model,
    ModelVariant variant,
    int outputTokens,
    String systemInstruction,
    List<ToolBinding> toolBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl) {

  public ModelRequestSpec {
    if (catalogEpoch <= 0) {
      throw new IllegalArgumentException("catalogEpoch must be positive");
    }
    providerType = Objects.requireNonNull(providerType, "providerType");
    providerConnectionGenerationId =
        Objects.requireNonNull(providerConnectionGenerationId, "providerConnectionGenerationId");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
    if (outputTokens <= 0) {
      throw new IllegalArgumentException("outputTokens must be positive");
    }
    if (systemInstruction == null || systemInstruction.isBlank()) {
      throw new IllegalArgumentException("systemInstruction must not be blank");
    }
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
    requireUniqueToolBindings(toolBindings);
    requireUniqueSubagentNames(subagentBindings);
    requireConsistentEnvironments(toolBindings);
    requireConsistentCatalogEpoch(toolBindings, catalogEpoch);
  }

  /** 一次模型调用的工具面只来自一个目录 epoch：任何 binding 都必须与 spec 同 epoch。 */
  private static void requireConsistentCatalogEpoch(
      List<ToolBinding> toolBindings, long catalogEpoch) {
    for (ToolBinding binding : toolBindings) {
      if (binding.catalogEpoch() != catalogEpoch) {
        throw new IllegalArgumentException(
            "tool binding catalogEpoch must match the model request catalogEpoch");
      }
    }
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

  /** 一次模型调用只服务一个 Environment：任何携带环境的 binding 必须共享同一个 Environment 身份。 */
  private static void requireConsistentEnvironments(List<ToolBinding> toolBindings) {
    EnvironmentId environmentId = null;
    boolean environmentSeen = false;
    for (ToolBinding binding : toolBindings) {
      if (binding.environmentId() == null) {
        continue;
      }
      if (!environmentSeen) {
        environmentId = binding.environmentId();
        environmentSeen = true;
      } else if (!Objects.equals(environmentId, binding.environmentId())) {
        throw new IllegalArgumentException("environment-bound tools must share one environment");
      }
    }
  }
}
