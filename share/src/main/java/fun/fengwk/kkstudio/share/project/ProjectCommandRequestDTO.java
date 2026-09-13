package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 给 Project Coordinator Session 发送命令的请求 DTO。
 *
 * <p>首次消息触发原子 bootstrap；已存在 Session 时对既有 Thread 追加消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCommandRequestDTO {

  private String idempotencyKey;
  private String message;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String threadId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String expectedHeadEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String expectedNextCommandSequence;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
