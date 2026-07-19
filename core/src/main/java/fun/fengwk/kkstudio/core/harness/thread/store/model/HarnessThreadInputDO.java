package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread_input} 行映射：有序 Thread mailbox。 */
@Data
public class HarnessThreadInputDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Thread。 */
  private Long threadId;

  /** Thread 内有序序号（从 1 递增）。 */
  private Long sequence;

  /** 输入类型：user_message / custom_message / set_*。 */
  private String inputType;

  /** Payload JSON。 */
  private String payloadJson;

  /** 客户端幂等键；可空。 */
  private String clientMessageId;

  /** 状态：queued / applied / cancelled。 */
  private String status;

  /** 应用后产生的 Entry id；未应用为空。 */
  private Long appliedEntryId;

  /** 应用或取消时间。 */
  private LocalDateTime resolvedAt;

  /** 取消该 Input 的 Stop id。 */
  private Long cancelledByStopId;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
