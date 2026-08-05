package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Stop 结果。
 *
 * <p>{@code status} 为 STOPPED / IDLE / REPLAYED；IDLE 表示未停止任何 Turn 但可能仍取消了 queued Commands，{@code
 * stoppedTurnEndEntryId} 仅在 STOPPED / REPLAYED 时非 null。
 */
@Data
public class HarnessThreadStopResultDTO {
  private String status;
  private HarnessThreadDTO thread;
  private String stoppedTurnEndEntryId;
  private Integer cancelledCommandCount;
}
