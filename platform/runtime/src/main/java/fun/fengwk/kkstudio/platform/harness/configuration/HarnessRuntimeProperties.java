package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness Runtime 的纯 bootstrap 部署配置：进程内 worker 开关。
 *
 * <p>Processor、compaction 与 subagent 等运行软策略由数据库 SystemSettings 承载；Dispatcher 容量与调度节奏由独立的 {@link
 * HarnessDispatcherProperties} 承载。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  /** 是否启动本进程的 Work dispatcher / listener；关闭时控制/查询平面仍然可用。 */
  private boolean workersEnabled = true;
}
