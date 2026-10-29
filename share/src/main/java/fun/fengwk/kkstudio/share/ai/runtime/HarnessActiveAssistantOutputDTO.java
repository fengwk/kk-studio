package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 当前 open Turn 的公共 Assistant 实时输出投影。
 *
 * <p>它只是 Driver 当前状态的快照，不是新的输出表；{@code resultEntryId} 到达后由 durable Entry 原位接管。内容只包含已完成的
 * text/thinking 片段，前端不得解析 Provider 原始 JSON。
 */
@Data
public class HarnessActiveAssistantOutputDTO {

  /** 稳定输出/ generation 身份（canonical UUID string）。 */
  private String generationId;

  /** 所属 Turn 的 TURN_START Entry 身份。 */
  private String turnStartEntryId;

  /** 本次 generation 冻结的 request head Entry 身份。 */
  private String requestHeadEntryId;

  /** 生命周期状态：READY / RUNNING / WAITING_RETRY / SUCCEEDED / FAILED / CANCELLED / BLOCKED。 */
  private String status;

  /** 已持久化 RUNNING 的执行次数（非负整数）。 */
  private Integer attempt;

  /** 当前已完成输出覆盖到的 canonical 非负十进制 sequence；无输出时为 "0"。 */
  private String sequence;

  /** 有序的已完成 text/thinking 片段。 */
  private List<HarnessAgentMessageContentDTO> contents = List.of();

  /** 终止错误；无错误时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable HarnessAssistantErrorDTO error;

  /** 已挂接的结果 Entry；尚未物化时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String resultEntryId;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
