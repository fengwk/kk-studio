package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Issue Activity 公开传输对象：Issue 唯一有序事实流上的一条记录。
 *
 * <p>{@code data} 是作为无类型 JSON 对象承载的类型化事件有效负载，对于 RUN/COMMENT/INSTRUCTION 不重复承载。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueActivityDTO {

  private String issueId;
  private String sequence;
  private String kind;
  private String actorType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String actorAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String runId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String body;

  private Object data;
  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
