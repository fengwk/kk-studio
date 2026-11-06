package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;

import java.time.Instant;

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
  private Long sequence;
  private IssueActivityKind kind;
  private IssueActivityActorType actorType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String actorAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String runId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String body;

  private @Nullable Object data;
  private Instant createdAt;
}
