package fun.fengwk.kkstudio.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
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

    public AgentRuntimeConfigResolver(AgentRegistry agentRegistry,
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

    public ResolvedRuntimeConfig resolve(String agentName,
                                         String provider,
                                         String model,
                                         String variant) {
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
            throw new IllegalArgumentException("model not found: " + resolvedProvider + "/" + resolvedModel);
        }
        String resolvedVariant = firstNonBlank(variant, latestAgentInfo.getDefaultVariant(), modelInfo.getDefaultVariant());
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
        ModelRequestConfig modelRequestConfig = buildModelRequestConfig(modelInfo, variantInfo, latestAgentInfo);
        return ResolvedRuntimeConfig.builder()
            .agentInfo(latestAgentInfo)
            .agentPayload(agentPayload)
            .modelPayload(modelPayload)
            .provider(runtimeProvider)
            .modelRequestConfig(modelRequestConfig)
            .resolvedProvider(resolvedProvider)
            .resolvedModel(resolvedModel)
            .resolvedVariant(variantInfo.getName())
            .build();
    }

    private ModelRequestConfig buildModelRequestConfig(ModelInfo modelInfo, Variant variantInfo, AgentInfo latestAgentInfo) {
        List<ToolSpecification> toolSpecifications = resolveToolSpecifications(latestAgentInfo == null ? List.of() : latestAgentInfo.getTools());
        return ModelRequestConfig.builder()
            .modelName(modelInfo.getName())
            .temperature(variantInfo.getTemperature())
            .topP(variantInfo.getTopP())
            .topK(variantInfo.getTopK())
            .frequencyPenalty(variantInfo.getFrequencyPenalty())
            .presencePenalty(variantInfo.getPresencePenalty())
            .maxOutputTokens(variantInfo.getMaxOutputTokens())
            .stopSequences(variantInfo.getStopSequences())
            .toolSpecifications(toolSpecifications.isEmpty() ? null : toolSpecifications)
            .build();
    }

    private List<ToolSpecification> resolveToolSpecifications(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (String toolName : toolNames) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            ToolInfo toolInfo = toolRegistry.getToolInfo(toolName);
            if (toolInfo == null) {
                continue;
            }
            result.add(ToolSpecification.builder()
                .name(toolInfo.getName())
                .description(toolInfo.getDescription())
                .parameters(JsonObjectSchema.builder().build())
                .build());
        }
        return result;
    }

    private SetAgentInfoPayload toSetAgentInfoPayload(AgentInfo latestAgentInfo) {
        SetAgentInfoPayload payload = new SetAgentInfoPayload();
        payload.setAgentName(latestAgentInfo.getName());
        payload.setSystemPrompt(latestAgentInfo.getSystemPrompt());
        payload.setTools(copyList(latestAgentInfo.getTools()));
        payload.setSubagents(copyList(latestAgentInfo.getSubagents()));
        payload.setSkills(copyList(latestAgentInfo.getSkills()));
        return payload;
    }

    private Variant resolveVariant(ModelInfo modelInfo, String variantName) {
        if (modelInfo.getVariants() == null || modelInfo.getVariants().isEmpty()) {
            throw new IllegalArgumentException("model variants must not be empty: " + modelInfo.getName());
        }
        for (Variant candidate : modelInfo.getVariants()) {
            if (candidate != null && Objects.equals(candidate.getName(), variantName)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("variant not found: " + modelInfo.getName() + "/" + variantName);
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
        private final ModelRequestConfig modelRequestConfig;
        private final String resolvedProvider;
        private final String resolvedModel;
        private final String resolvedVariant;
    }

}
