package fun.fengwk.kkstudio.platform.catalog.provider;

import lombok.Builder;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Instant;
import java.util.UUID;

/**
 * 全局 Agent provider 资源（{@code agent_provider} 行）。
 *
 * <p>{@code credential} 为敏感字段：不进入公开 DTO，仅在构造 Provider 运行时适配器时使用。{@code configJson} 为 jsonb
 * 配置（模型调用超时与 HTTP 重试白名单）。{@code connectionGenerationId} 仅在协议、凭据或连接配置变化时轮换。{@code version} 从 0
 * 开始，每次写入 +1，作为 CAS 依据；新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record AgentProvider(
    String name,
    String description,
    ProviderType providerType,
    String baseUrl,
    String credential,
    String configJson,
    UUID connectionGenerationId,
    Long version,
    Instant createTime,
    Instant updateTime) {

  /** 不输出凭据与配置，避免经日志泄露。 */
  @Override
  public String toString() {
    return "AgentProvider[name="
        + name
        + ", providerType="
        + providerType
        + ", version="
        + version
        + "]";
  }
}
