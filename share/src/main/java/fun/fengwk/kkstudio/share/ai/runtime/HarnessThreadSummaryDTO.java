package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Runtime Session 下的 Thread 摘要。 */
@Data
public class HarnessThreadSummaryDTO {

  /** Thread 主键：canonical UUID string。 */
  private String threadId;

  /** Thread 创建时间（UTC Instant）。 */
  private Instant createdAt;

  /** Thread 最后更新时间（UTC Instant）。 */
  private Instant updatedAt;

  /** 从当前 Thread context、Invocation 与 queued Command 派生的状态。 */
  private String status;

  /** 当前 head branch 的完整 Model selection。 */
  private HarnessModelSelectionDTO model;

  /** 当前 head 上最近的用户可读消息预览；没有消息时显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String headMessagePreview;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown harness thread summary field: " + field);
  }
}
