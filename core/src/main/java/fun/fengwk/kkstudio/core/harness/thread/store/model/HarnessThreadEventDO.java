package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread_event} 行映射：Thread 事件 journal。 */
@Data
public class HarnessThreadEventDO {
  /** 全局事件 id，亦为 SSE cursor。 */
  private Long id;

  /** 所属 Thread。 */
  private Long threadId;

  /** 关联 Entry（如 planned assistant）；可空。 */
  private Long subjectEntryId;

  /** 事件类型。 */
  private String eventType;

  /** 含 schemaVersion 的 payload JSON。 */
  private String payloadJson;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
