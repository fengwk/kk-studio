package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Stop 结果。
 *
 * <p>{@code status} 为 STOPPED / IDLE / REPLAYED：STOPPED 表示本次 Stop 写下了本线程自己的停止边界；IDLE
 * 表示本线程没有可写的停止边界（open Turn 属于其它线程的共享历史），可能只取消了 queued Commands；REPLAYED 表示重放了先前 Stop 的 durable
 * receipt。
 */
@Data
public class HarnessThreadStopResultDTO {
  /**
   * 结果状态，取 {@code StopStatus} 枚举名：STOPPED（已写下本线程的停止边界）/ IDLE（本线程没有可写的停止边界，但可能取消了 queued Commands）/
   * REPLAYED。
   */
  private String status;

  /** 停止操作后的权威 Thread 投影（与结果 version 一致）。 */
  private HarnessThreadDTO thread;

  /**
   * 本线程停止边界 TURN_END 的主键：canonical UUID string；关闭 live Turn 或为空闲 Stop 写入的 STOP barrier Turn 都计入，二者
   * 都会使 head 推进到该 TURN_END。本线程没有可写的停止边界或 queued-only replay 时为 null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String stoppedTurnEndEntryId;

  /** 本次 stop 取消的 queued 命令数（非负整数；IDLE 时也可能大于 0）。 */
  private Integer cancelledCommandCount;

  /** 被取消的 USER_MESSAGE / USER role CUSTOM_MESSAGE，按 command sequence 升序。 */
  private List<HarnessCancelledUserMessageDTO> cancelledUserMessages = List.of();
}
