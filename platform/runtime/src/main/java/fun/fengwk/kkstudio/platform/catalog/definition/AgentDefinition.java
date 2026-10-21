package fun.fengwk.kkstudio.platform.catalog.definition;

import lombok.Builder;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.time.Instant;

/**
 * 全局 Agent definition 资源（{@code agent_definition} 行）。
 *
 * <p>{@code name} 全局唯一，最长 64，不允许首尾空白与 '/'。{@code type} 由系统持有：用户创建为 USER，内置为 BUILTIN。{@code
 * (modelProviderName, modelName)} 组成复合外键引用 {@code agent_model}，USER 必填，未配置模型的内置 Agent 两列同为
 * null；{@code variant} 为空表示运行时取 Model 的 defaultVariant。{@code configJson} 为 jsonb 可执行配置（tools /
 * skills / subagents / inheritParentEnvironment）。{@code version} 从 0 开始，每次写入 +1，作为 CAS
 * 依据；新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record AgentDefinition(
    String name,
    AgentDefinitionType type,
    String description,
    String systemPrompt,
    String modelProviderName,
    String modelName,
    String variant,
    String configJson,
    Long version,
    Instant createTime,
    Instant updateTime) {}
