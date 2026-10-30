package fun.fengwk.kkstudio.harness.builtin.model;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

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
 */
public record ModelRequestSpec(
    ProviderType providerType,
    UUID providerConnectionGenerationId,
    ModelDescriptor model,
    ModelVariant variant,
    int outputTokens,
    String systemInstruction,
    List<ToolBinding> toolBindings,
    List<SubagentBinding> subagentBindings,
    ProviderCacheControl cacheControl,
    ModelGroupExecution groupExecution) {

  public ModelRequestSpec(
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
      List<ToolBinding> toolBindings,
      List<SubagentBinding> subagentBindings,
      ProviderCacheControl cacheControl) {
    this(
        providerType,
        providerConnectionGenerationId,
        model,
        variant,
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        cacheControl,
        null);
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
    if (systemInstruction == null || systemInstruction.isBlank()) {
      throw new IllegalArgumentException("systemInstruction must not be blank");
    }
    toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings"));
    subagentBindings = List.copyOf(Objects.requireNonNull(subagentBindings, "subagentBindings"));
    cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
    if (groupExecution != null && groupExecution.members().isEmpty()) {
      throw new IllegalArgumentException("groupExecution must carry members");
    }
    requireUniqueToolBindings(toolBindings);
    requireUniqueSubagentNames(subagentBindings);
    requireConsistentEnvironments(toolBindings);
  }

  /** 模型组在指定 durable 成员游标处应使用的执行快照；顶层字段被替换为该成员的真实身份。 */
  public ModelRequestSpec forMember(int memberIndex) {
    if (groupExecution == null) {
      throw new IllegalArgumentException("request spec is not a model group");
    }
    if (memberIndex < 0 || memberIndex >= groupExecution.members().size()) {
      throw new IllegalArgumentException("memberIndex is outside the frozen group members");
    }
    ModelGroupMemberExecution member = groupExecution.members().get(memberIndex);
    return new ModelRequestSpec(
        member.providerType(),
        member.providerConnectionGenerationId(),
        member.model(),
        member.variant(),
        outputTokens,
        systemInstruction,
        toolBindings,
        subagentBindings,
        cacheControl,
        groupExecution);
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
