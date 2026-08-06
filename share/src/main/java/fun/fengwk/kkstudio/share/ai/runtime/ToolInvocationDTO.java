package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * ToolInvocation 查询投影（tool_invocation 表）；id 均为 strict positive decimal string。
 *
 * <p>请求字段由 tool binding 与 call 派生；{@code approvalJson} / {@code resultJson} / {@code errorJson} 为
 * canonical runtime codec JSON，仅对应阶段非 null；{@code environmentId} 为 nullable canonical lowercase
 * UUID route identity。
 */
@Data
public class ToolInvocationDTO {
  private String id;
  private String modelInvocationId;
  private String assistantEntryId;
  private Integer ordinal;
  private String status;
  private Integer attempt;
  private String toolCallId;
  private String toolName;
  private String toolVersion;
  private String toolType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentId;

  private String argumentsJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String approvalJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String errorJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultEntryId;

  private Instant createTime;
  private Instant updateTime;
}
