package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Thread stop 请求。
 *
 * <p>{@code stopRequestId} 是稳定幂等键；{@code expectedRevision} 是 exact revision CAS cursor， 读取自最新
 * {@link HarnessThreadDTO}。
 */
@Data
public class HarnessThreadStopDTO {
  /** 必填稳定幂等键（canonical UUID string）：同一 stop 请求重放返回既有结果。 */
  private String stopRequestId;

  /** 必填 exact revision CAS 游标：strict non-negative decimal string，读取自最新 {@link HarnessThreadDTO}。 */
  private String expectedRevision;
}
