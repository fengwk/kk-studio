package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 异步 task 结算扫描的部署级参数。
 *
 * <p>这些值是进程启动边界，不属于 SystemSettings、DTO 或前端配置：扫描间隔决定结果交付与停止传播的延迟上界，批量大小决定单次扫描的工作量。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.task")
public class SubagentTaskProperties {

  public static final Duration DEFAULT_SETTLEMENT_INTERVAL = Duration.ofSeconds(1);
  public static final int DEFAULT_SETTLEMENT_BATCH_SIZE = 32;

  private Duration settlementInterval = DEFAULT_SETTLEMENT_INTERVAL;
  private int settlementBatchSize = DEFAULT_SETTLEMENT_BATCH_SIZE;

  public Duration getSettlementInterval() {
    if (settlementInterval == null
        || settlementInterval.isNegative()
        || settlementInterval.isZero()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.task.settlement-interval must be positive");
    }
    return settlementInterval;
  }

  public void setSettlementInterval(Duration settlementInterval) {
    this.settlementInterval = settlementInterval;
  }

  public int getSettlementBatchSize() {
    if (settlementBatchSize < 1) {
      throw new IllegalArgumentException(
          "kk-studio.harness.task.settlement-batch-size must be at least 1");
    }
    return settlementBatchSize;
  }

  public void setSettlementBatchSize(int settlementBatchSize) {
    this.settlementBatchSize = settlementBatchSize;
  }
}
