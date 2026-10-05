package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Thread Stop 结果。
 *
 * <p>{@code status} 为 STOPPED / REPLAYED：STOPPED 表示本次 Stop 已在目标 Thread 与完整后代上写下停止边界；REPLAYED
 * 表示精确重放了先前 Stop 的 durable 回执。{@code thread} 是请求目标的权威当前投影；{@code stoppedThreads} 是本次完整受影响集合（重放时为原
 * 回执集合）的持久回执。
 */
@Data
public class HarnessThreadStopResultDTO {

  /** 结果状态，取 {@code StopStatus} 语义：STOPPED 或 REPLAYED。 */
  private String status;

  /** 停止操作后的权威 Thread 投影（与结果 version 一致）。 */
  private HarnessThreadDTO thread;

  /** 本次 Stop 的完整受影响集合回执（含目标自身），按 Runtime 的确定性顺序。 */
  private List<HarnessStoppedThreadReceiptDTO> stoppedThreads = List.of();
}
