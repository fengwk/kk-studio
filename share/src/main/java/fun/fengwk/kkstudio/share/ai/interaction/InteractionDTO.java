package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * 一个待处理 Interaction（问卷等待或审批等待）的稳定投影。
 *
 * <p>与 Pane 读取同一事实源：{@code status} 是 {@code WAITING_INPUT} 或 {@code WAITING_APPROVAL}；{@code owner}
 * 提供 Pane 跳转所需的产品归属；{@code threadId} / {@code sessionId} 提供原始调用的 Harness 坐标。来源可能位于执行树任意 深度，{@code
 * rootThreadId} 始终指向该来源所属执行树的真实根，供根面板与全局交互中心按根过滤和展示，不替换来源坐标。 问卷等待时 {@code argumentsJson}
 * 是冻结问卷原文，审批等待时 {@code approvalJson} 是 durable approval 事实。
 */
@Data
public class InteractionDTO {

  private String interactionId;

  private String status;

  /** 来源 Tool invocation 所在 Thread，保留原始调用来源，不替代为根。 */
  private String threadId;

  private String sessionId;

  /** 来源所属执行树的真实根 Thread：后代来源也指向同一根，永远非空；{@code threadId} 自身即根时与其相等。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String rootThreadId;

  private InteractionOwnerDTO owner;

  private String toolCallId;

  private String toolName;

  private String argumentsJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String approvalJson;

  private Instant createTime;
}
