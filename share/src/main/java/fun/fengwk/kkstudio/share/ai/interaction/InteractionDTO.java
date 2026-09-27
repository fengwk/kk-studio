package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * 一个待处理 Interaction（问卷等待或审批等待）的稳定投影。
 *
 * <p>与 Pane 读取同一事实源：{@code status} 是 {@code WAITING_INPUT} 或 {@code WAITING_APPROVAL}；{@code owner}
 * 提供 Pane 跳转所需的产品归属；{@code threadId} / {@code sessionId} 提供 Harness 坐标。问卷等待时 {@code argumentsJson}
 * 是冻结问卷原文， 审批等待时 {@code approvalJson} 是 durable approval 事实。
 */
@Data
public class InteractionDTO {

  private String interactionId;

  private String status;

  private String threadId;

  private String sessionId;

  private InteractionOwnerDTO owner;

  private String toolCallId;

  private String toolName;

  private String argumentsJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String approvalJson;

  private Instant createTime;
}
