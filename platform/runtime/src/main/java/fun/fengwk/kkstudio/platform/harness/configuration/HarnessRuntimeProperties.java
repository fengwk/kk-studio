package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Harness Runtime 的纯 bootstrap 部署配置：进程内 worker 开关与优雅关闭排空超时。
 *
 * <p>Processor、compaction 与 subagent 等运行软策略由数据库 SystemSettings 承载；Dispatcher 容量与调度节奏由独立的 {@link
 * HarnessDispatcherProperties} 承载。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  /** 是否启动本进程的 Work dispatcher / listener；关闭时控制/查询平面仍然可用。 */
  private boolean workersEnabled = true;

  /** 节点优雅关闭排空超时：等待活跃 Model/Tool 执行完成；超时后对仍等待远端结果的工具执行移交。 */
  private Duration drainTimeout = Duration.ofSeconds(60);
}
