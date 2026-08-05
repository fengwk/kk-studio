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
  private String stopRequestId;
  private String expectedRevision;
}
