package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Chat/Canvas owner 持有的 Harness Session 摘要。 */
@Data
public class HarnessSessionSummaryDTO {

  /** Session 主键：canonical UUID string。 */
  private String sessionId;

  /** Session 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  private String name;

  /** Session ROOT 创建时间（UTC Instant）。 */
  private Instant createdAt;

  /** Session 内 Entry 与 Thread 的最后活动时间（UTC Instant）。 */
  private Instant lastActivityAt;

  /** 按时间确定性选择的首条用户可读预览；没有消息时显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String firstMessagePreview;

  /** 当前 Session 的 Thread 数量。 */
  private int threadCount;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new HarnessRequestFormatException("unknown harness session summary field: " + field);
  }
}
