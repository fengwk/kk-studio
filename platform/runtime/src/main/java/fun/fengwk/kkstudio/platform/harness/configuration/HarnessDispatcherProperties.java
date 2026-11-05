package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Harness Work Dispatcher 的部署级容量与调度配置。
 *
 * <p>这些值是进程启动与容器部署边界，不属于 SystemSettings、DTO 或前端配置。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.dispatcher")
public class HarnessDispatcherProperties {

  private Duration leaseDuration = Duration.ofSeconds(30);
  private Duration pollInterval = Duration.ofSeconds(1);
  private Duration rejectionDelay = Duration.ofSeconds(1);
  private int maxDispatchTasks = 64;
  private Worker worker = new Worker();

  @Data
  public static class Worker {
    private int concurrency = 16;
    private int queueCapacity = 64;
  }
}
