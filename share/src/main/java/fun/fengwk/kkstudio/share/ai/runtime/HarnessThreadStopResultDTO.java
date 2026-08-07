package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Stop 结果。
 *
 * <p>{@code status} 为 STOPPED / IDLE / REPLAYED；IDLE 表示未停止任何 Turn 但可能仍取消了 queued Commands，{@code
 * stoppedTurnEndEntryId} 仅在 STOPPED / REPLAYED 时非 null。
 */
@Data
public class HarnessThreadStopResultDTO {
  /**
   * 结果状态，取 {@code StopStatus} 枚举名：STOPPED（已停止 Turn）/ IDLE（未停止任何 Turn，但可能取消了 queued Commands）/
   * REPLAYED。
   */
  private String status;

  /** 停止操作后的权威 Thread 投影（与结果 revision 一致）。 */
  private HarnessThreadDTO thread;

  /**
   * 被停止 Turn 的 TURN_END Entry 主键：strict positive decimal string；仅 STOPPED / REPLAYED 时非
   * null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String stoppedTurnEndEntryId;

  /** 本次 stop 取消的 queued 命令数（非负整数；IDLE 时也可能大于 0）。 */
  private Integer cancelledCommandCount;
}
