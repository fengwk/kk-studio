package fun.fengwk.kkstudio.platform.catalog.definition;

import lombok.Builder;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;

import java.time.Instant;

/**
 * 全局 Agent definition 资源（{@code agent} 行）。
 *
 * <p>{@code name} 全局唯一，最长 64，不允许首尾空白与 '/'。{@code type} 由系统持有：用户创建为 USER，内置为 BUILTIN。{@code
 * execution} 是 Driver 及其设置，USER 必填，未配置的内置 Agent 为 null。{@code configJson} 为 jsonb 可执行配置（tools /
 * skills / subagents）。{@code version} 从 0 开始，每次写入 +1，作为 CAS 依据；新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record Agent(
    String name,
    AgentType type,
    String description,
    String systemPrompt,
    ExecutionSelection execution,
    String configJson,
    Long version,
    Instant createTime,
    Instant updateTime) {}
