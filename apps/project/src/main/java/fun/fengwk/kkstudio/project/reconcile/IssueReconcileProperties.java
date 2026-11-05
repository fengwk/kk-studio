package fun.fengwk.kkstudio.project.reconcile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Issue Reconcile 的容量、租约与调度控制部署级配置。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.project.reconcile")
public class IssueReconcileProperties {

  private Duration leaseDuration = Duration.ofSeconds(30);
  private Duration pollInterval = Duration.ofSeconds(1);
  private Duration rejectionDelay = Duration.ofSeconds(1);
  private Duration retryDelay = Duration.ofSeconds(5);
  private Duration activeDelay = Duration.ofSeconds(1);
  private Duration blockedDelay = Duration.ofSeconds(60);
  private int maxDispatchTasks = 64;
  private Worker worker = new Worker();

  @Data
  public static class Worker {
    private int concurrency = 8;
    private int queueCapacity = 64;
  }
}
