package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * ModelInvocation 查询投影（model_invocation 表）；id 均为 strict positive decimal string。
 *
 * <p>{@code streamCheckpointJson} / {@code resultJson} / {@code errorJson} 为 canonical runtime
 * codec JSON，仅对应阶段非 null；{@code resultEntryId} 为 TURN_END 应用后的结果 Entry。
 */
@Data
public class ModelInvocationDTO {
  private String id;
  private String threadId;
  private String turnStartEntryId;
  private String basisHeadEntryId;
  private String status;
  private Integer attempt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String streamCheckpointJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String errorJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultEntryId;

  private Instant createTime;
  private Instant updateTime;
}
