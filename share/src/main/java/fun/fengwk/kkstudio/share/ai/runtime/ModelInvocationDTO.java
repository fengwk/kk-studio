package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * ModelInvocation 查询投影（model_invocation 表）；实体 id 均为 canonical UUID string。
 *
 * <p>{@code streamCheckpointJson} / {@code resultJson} / {@code errorJson} 为 canonical runtime
 * codec JSON，仅对应阶段非 null；{@code resultEntryId} 为 TURN_END 应用后的结果 Entry。
 */
@Data
public class ModelInvocationDTO {
  /** Invocation 主键：canonical UUID string。 */
  private String id;

  /** 所属 Thread 主键：canonical UUID string。 */
  private String threadId;

  /** 所属 Turn 的 TURN_START Entry 主键：canonical UUID string。 */
  private String turnStartEntryId;

  /** 调用创建时的 head Entry 主键（basis）：canonical UUID string。 */
  private String basisHeadEntryId;

  /**
   * 状态，取 {@code ModelInvocationStatus} 枚举名：READY / DISPATCHING / RUNNING / SUCCEEDED / FAILED /
   * CANCELLED / UNKNOWN。
   */
  private String status;

  /** 已确认的 Provider 调用开始次数（非负整数）；BUSY/OVERLOADED 等未确认启动不计数。 */
  private Integer attempt;

  /**
   * 当前尝试的安全流式部分：canonical JSON（StreamCheckpointJsonCodec）；仅 RUNNING 阶段可能非
   * null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String streamCheckpointJson;

  /** 成功结果：canonical JSON（ProviderResponseJsonCodec）；仅成功阶段非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultJson;

  /**
   * 错误详情：canonical JSON（ModelInvocationErrorJsonCodec）；仅记录错误后非 null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String errorJson;

  /** 结果挂载的 Entry 主键：canonical UUID string；TURN_END 应用后非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultEntryId;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
