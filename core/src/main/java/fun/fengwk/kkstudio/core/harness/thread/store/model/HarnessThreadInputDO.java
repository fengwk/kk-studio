package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread_input} 行映射：有序 Thread 输入。 */
@Data
public class HarnessThreadInputDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Thread。 */
  private Long threadId;

  /** Thread 内有序序号（从 1 递增）。 */
  private Long sequence;

  /** 输入类型：user_message / set_agent / set_yolo。 */
  private String inputType;

  /** Payload JSON。 */
  private String payloadJson;

  /** 客户端幂等键；可空。 */
  private String clientMessageId;

  /** 应用后产生的 Entry id；未应用为空。 */
  private Long appliedEntryId;

  /** 应用时间；未应用为空。 */
  private LocalDateTime appliedAt;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
