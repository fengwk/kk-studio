package fun.fengwk.kkstudio.platform.catalog.model;

import lombok.Builder;

import java.time.Instant;

/**
 * 全局 Agent model 资源（{@code agent_model} 行）。
 *
 * <p>{@code (providerName, name)} 为复合主键，{@code name} 可重命名；{@code modelId} 是发往上游 Provider
 * 的真实模型标识，不唯一。 {@code configJson} 为 jsonb 结构化配置（limit / abilities / pricing / variants /
 * defaultVariant）。{@code version} 从 0 开始，每次写入 +1，作为 CAS 依据；新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record AgentModel(
    String providerName,
    String name,
    String modelId,
    String description,
    String configJson,
    Long version,
    Instant createTime,
    Instant updateTime) {}
