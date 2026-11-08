package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 当前 Daemon 进程的运行配置状态。
 *
 * <p>{@code appliedVersion} 缺失表示当前进程尚无可用配置；存在时 {@code proxyUrl} 缺失表示显式直连。{@code error}
 * 是最近一次配置应用失败的稳定规则结果。
 */
@Data
public class EnvironmentRuntimeConfigurationDTO {
  private @Nullable Long appliedVersion;

  /** 已应用版本的代理；缺失表示明确直连，不是未知。 */
  private @Nullable String proxyUrl;

  private @Nullable EnvironmentRuntimeConfigurationErrorDTO error;
}
