package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Stop 结果。
 *
 * <p>{@code status} 为 STOPPED / IDLE / REPLAYED；IDLE 表示未停止任何 Turn 但可能仍取消了 queued Commands。{@code
 * stoppedTurnEndEntryId} 仅在确实停止过 live Turn 时非 null，queued-only replay 仍为 null。
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
   * 被停止 Turn 的 TURN_END Entry 主键：canonical UUID string；未停止 live Turn（含 queued-only replay）时为
   * null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String stoppedTurnEndEntryId;

  /** 本次 stop 取消的 queued 命令数（非负整数；IDLE 时也可能大于 0）。 */
  private Integer cancelledCommandCount;

  /** 被取消的 USER_MESSAGE / USER role CUSTOM_MESSAGE，按 command sequence 升序。 */
  private List<HarnessCancelledUserMessageDTO> cancelledUserMessages = List.of();
}
