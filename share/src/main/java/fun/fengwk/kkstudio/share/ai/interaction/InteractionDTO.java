package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * 一个待处理 Interaction 的稳定投影，由 {@code type} 区分三种只读条目：
 *
 * <ul>
 *   <li>{@code INPUT}：等待人工输入的 {@code WAITING_INPUT} ToolInvocation；{@code interactionId} 是原始调用主键，可经
 *       {@code /api/interactions/{interactionId}/input} 提交问卷；
 *   <li>{@code APPROVAL}：等待审批的 {@code WAITING_APPROVAL} ToolInvocation；{@code interactionId}
 *       是原始调用主键，携带 durable {@code approvalJson}；
 *   <li>{@code ENVIRONMENT_WAIT}：按 {@code (真实执行根, 所需环境)} 聚合的一组待领取 TOOL 调用的只读投影。它没有可操作的 interaction
 *       主键、状态或审批事实，只有 {@code environmentId} / {@code environmentName} / {@code
 *       waitingCount}；排序/游标代表是最早未完成调用， 但绝不冒充可操作的 interaction。
 * </ul>
 *
 * <p>与 Pane 读取同一事实源：人工等待条目携带 {@code threadId} / {@code sessionId} 的原始调用坐标与冻结的
 * ToolCall；环境等待条目只保留聚合身份。 {@code owner} 提供 Pane 跳转所需的产品归属；{@code rootThreadId}
 * 始终指向该来源所属执行树的真实根，供根面板与全局交互中心按根过滤和展示。
 */
@Data
public class InteractionDTO {

  /** 条目类型：{@code INPUT} / {@code APPROVAL} / {@code ENVIRONMENT_WAIT}。 */
  private String type;

  /** 人工等待条目的原始 ToolInvocation 主键；环境等待条目没有可操作主键，为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String interactionId;

  /** 人工等待条目的等待状态（{@code WAITING_INPUT} / {@code WAITING_APPROVAL}）；环境等待条目为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String status;

  /** 来源 Tool invocation 所在 Thread，保留原始调用来源，不替代为根；环境等待条目为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String threadId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sessionId;

  /** 来源所属执行树的真实根 Thread：后代来源也指向同一根，永远非空；{@code threadId} 自身即根时与其相等。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String rootThreadId;

  private InteractionOwnerDTO owner;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String toolCallId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String toolName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String argumentsJson;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String approvalJson;

  /** 环境等待条目的冻结所需环境；非环境条目为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentId;

  /** 环境等待条目的环境展示名称；非环境条目为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** 环境等待条目的组内待领取调用数；非环境条目为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer waitingCount;

  /** 人工等待为原始调用创建时间；环境等待为组内最早调用的创建时间（排序/游标代表）。 */
  private Instant createTime;
}
