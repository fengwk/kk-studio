package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** advanced section：processor/dispatcher/executor/事件通道/工作通知的进程级运行预算。 */
@Data
public class SystemSettingsAdvancedDTO {

  private Long resourceMaxBytes;

  private Long processorLeaseDurationMillis;

  private Long processorHeartbeatIntervalMillis;

  private Long threadResolveFailureDelayMillis;

  private Long modelDispatchBusyFallbackDelayMillis;

  private Long toolPreflightFailureDelayMillis;

  private Long toolDispatchBusyFallbackDelayMillis;

  private Long dispatcherLeaseDurationMillis;

  private Long dispatcherPollIntervalMillis;

  private Long dispatcherRejectionDelayMillis;

  private Integer dispatcherMaxDispatchTasks;

  private Integer dispatcherWorkerConcurrency;

  private Integer dispatcherWorkerQueueCapacity;

  private Integer canvasFunctionExecutorCoreSize;

  private Integer canvasFunctionExecutorMaxSize;

  private Integer canvasFunctionExecutorQueueCapacity;

  private Integer applicationEventQueueCapacity;

  private Long applicationEventMaxBytes;

  private Long applicationEventSendTimeoutMillis;

  private Long applicationEventHeartbeatIntervalMillis;

  private Long postgresqlWorkNotificationPollMillis;

  private Long postgresqlWorkReconnectBackoffMillis;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings advanced field: " + name);
  }
}
