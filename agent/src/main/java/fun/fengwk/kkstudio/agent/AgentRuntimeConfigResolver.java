package fun.fengwk.kkstudio.agent;

import lombok.Builder;
import lombok.Data;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.ToolRegistration;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentRuntimeConfigResolver 负责把当前 agent/model 选择解析为实际运行配置。
 *
 * @author fengwk
 */
public class AgentRuntimeConfigResolver {

  private final AgentRegistry agentRegistry;
  private final ModelRegistry modelRegistry;
  private final ProviderRegistry providerRegistry;
  private final ProviderManager providerManager;
  private final ToolRegistry toolRegistry;

  public AgentRuntimeConfigResolver(
      AgentRegistry agentRegistry,
      ModelRegistry modelRegistry,
      ProviderRegistry providerRegistry,
      ProviderManager providerManager,
      ToolRegistry toolRegistry) {
    this.agentRegistry = requireNonNull(agentRegistry, "agentRegistry");
    this.modelRegistry = requireNonNull(modelRegistry, "modelRegistry");
    this.providerRegistry = requireNonNull(providerRegistry, "providerRegistry");
    this.providerManager = requireNonNull(providerManager, "providerManager");
    this.toolRegistry = requireNonNull(toolRegistry, "toolRegistry");
  }

  public ResolvedRuntimeConfig resolve(
      String agentName, String provider, String model, String variant) {
    AgentInfo latestAgentInfo = agentRegistry.getAgent(agentName);
    if (latestAgentInfo == null) {
      throw new IllegalArgumentException("agent not found: " + agentName);
    }

    SetAgentInfoPayload agentPayload = toSetAgentInfoPayload(latestAgentInfo);
    String resolvedProvider = firstNonBlank(provider, latestAgentInfo.getDefaultProvider());
    String resolvedModel = firstNonBlank(model, latestAgentInfo.getDefaultModel());
    if (resolvedProvider == null || resolvedModel == null) {
      throw new IllegalArgumentException("model selection must not be blank");
    }

    ModelInfo modelInfo = modelRegistry.getModel(resolvedProvider, resolvedModel);
    if (modelInfo == null) {
      throw new IllegalArgumentException(
          "model not found: " + resolvedProvider + "/" + resolvedModel);
    }
    String resolvedVariant =
        firstNonBlank(variant, latestAgentInfo.getDefaultVariant(), modelInfo.getDefaultVariant());
    Variant variantInfo = resolveVariant(modelInfo, resolvedVariant);

    SetModelInfoPayload modelPayload = new SetModelInfoPayload();
    modelPayload.setProvider(resolvedProvider);
    modelPayload.setModel(resolvedModel);
    modelPayload.setVariant(variantInfo.getName());

    ProviderInfo providerInfo = providerRegistry.getProviderInfo(resolvedProvider);
    if (providerInfo == null) {
      throw new IllegalArgumentException("provider info not found: " + resolvedProvider);
    }
    Provider runtimeProvider = providerManager.getProvider(providerInfo);
    Map<String, ToolRegistration> resolvedTools = resolveTools(latestAgentInfo.getTools());
    return ResolvedRuntimeConfig.builder()
        .agentInfo(latestAgentInfo)
        .agentPayload(agentPayload)
        .modelPayload(modelPayload)
        .provider(runtimeProvider)
        .modelInfo(modelInfo)
        .variant(variantInfo)
        .resolvedTools(resolvedTools)
        .resolvedProvider(resolvedProvider)
        .resolvedModel(resolvedModel)
        .resolvedVariant(variantInfo.getName())
        .build();
  }

  /** 将 Agent 声明解析为当前 assistant attempt 使用的工具注册项。 */
  private Map<String, ToolRegistration> resolveTools(List<String> toolNames) {
    if (toolNames == null || toolNames.isEmpty()) {
      return Map.of();
    }
    Map<String, ToolRegistration> result = new LinkedHashMap<>();
    for (String toolName : toolNames) {
      if (toolName == null || toolName.isBlank() || result.containsKey(toolName)) {
        continue;
      }
      ToolRegistration registration = toolRegistry.get(toolName);
      if (registration != null) {
        result.put(toolName, registration);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private SetAgentInfoPayload toSetAgentInfoPayload(AgentInfo latestAgentInfo) {
    SetAgentInfoPayload payload = new SetAgentInfoPayload();
    payload.setAgentName(latestAgentInfo.getName());
    payload.setSystemPrompt(latestAgentInfo.getSystemPrompt());
    payload.setTools(copyList(latestAgentInfo.getTools()));
    return payload;
  }

  private Variant resolveVariant(ModelInfo modelInfo, String variantName) {
    if (modelInfo.getVariants() == null || modelInfo.getVariants().isEmpty()) {
      throw new IllegalArgumentException(
          "model variants must not be empty: " + modelInfo.getName());
    }
    for (Variant candidate : modelInfo.getVariants()) {
      if (candidate != null && Objects.equals(candidate.getName(), variantName)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "variant not found: " + modelInfo.getName() + "/" + variantName);
  }

  private <T> List<T> copyList(List<T> list) {
    return list == null ? null : List.copyOf(list);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  @Builder
  @Data
  public static class ResolvedRuntimeConfig {
    private final AgentInfo agentInfo;
    private final SetAgentInfoPayload agentPayload;
    private final SetModelInfoPayload modelPayload;
    private final Provider provider;
    private final ModelInfo modelInfo;
    private final Variant variant;

    /** 当前 attempt 已解析的工具注册项，key 为声明工具名。 */
    private final Map<String, ToolRegistration> resolvedTools;

    private final String resolvedProvider;
    private final String resolvedModel;
    private final String resolvedVariant;

    public List<ToolInfo> getToolInfos() {
      if (resolvedTools == null || resolvedTools.isEmpty()) {
        return List.of();
      }
      return resolvedTools.values().stream().map(ToolRegistration::getToolInfo).toList();
    }
  }
}
