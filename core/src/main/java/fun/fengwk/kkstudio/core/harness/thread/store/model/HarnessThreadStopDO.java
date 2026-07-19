package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread_stop} 行映射：Stop 网络幂等回执。 */
@Data
public class HarnessThreadStopDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Thread。 */
  private Long threadId;

  /** 客户端稳定 Stop 请求幂等键。 */
  private String clientRequestId;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
