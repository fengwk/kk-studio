package fun.fengwk.kkstudio.platform.catalog.model;

import lombok.Builder;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.time.Instant;

/**
 * 全局 Agent model 资源（{@code agent_model} 行）：单 MODEL 或按序降级的 MODEL GROUP。
 *
 * <p>{@code name} 是单 Model 与模型组共享的全局主键，可重命名。MODEL 携带 {@code providerName}、{@code modelId}（发往上游
 * Provider 的真实模型标识，不唯一）与 jsonb 结构化配置 {@code configJson}；GROUP 只携带组配置 {@code groupConfigJson}。
 * {@code version} 从 0 开始，每次写入 +1，作为 CAS 依据；新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record AgentModel(
    String name,
    AgentModelKind kind,
    String providerName,
    String modelId,
    String description,
    String configJson,
    String groupConfigJson,
    Long version,
    Instant createTime,
    Instant updateTime) {}
