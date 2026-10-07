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

  /** 执行父 Thread 的 canonical UUID string；根 Thread 显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String parentThreadId;

  /** Thread 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  private String name;

  /** Thread 创建时间（UTC Instant）。 */
  private Instant createdAt;

  /** Thread 最后更新时间（UTC Instant）。 */
  private Instant updatedAt;

  /** 从当前 Thread context、Invocation 与 queued Command 派生的状态（只描述该 Thread 自身的执行）。 */
  private String status;

  /**
   * 该 Thread（含其委派子树）当前是否仍在处理。
   *
   * <p>异步 task 的父 Thread 在等待子结果时自身已静止，但工作并未结束；因此本字段是"自身执行中或子树仍有未交付委派"的聚合，{@code status} 为 {@code
   * IDLE} 时它仍可能为 true。
   */
  private boolean processing;

  /** 当前 head branch 的完整 Model selection。 */
  private HarnessModelSelectionDTO model;

  /** 当前 head 上最近的用户可读消息预览；没有消息时显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String headMessagePreview;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new HarnessRequestFormatException("unknown harness thread summary field: " + field);
  }
}
