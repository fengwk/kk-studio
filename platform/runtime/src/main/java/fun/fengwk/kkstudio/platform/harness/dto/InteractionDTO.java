package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 一个待处理 Interaction 的稳定投影，由 {@code type} 区分三种只读条目：
 *
 * <ul>
 *   <li>{@code INPUT}：等待人工输入的 {@code WAITING_INPUT} ToolInvocation；{@code toolInvocationId}
 *       是原始调用主键， 可经 {@code POST
 *       /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input} 提交问卷；
 *   <li>{@code APPROVAL}：等待审批的 {@code WAITING_APPROVAL} ToolInvocation；{@code toolInvocationId}
 *       是原始调用主键，携带 durable {@code approvalJson}；
 *   <li>{@code ENVIRONMENT_WAIT}：按 {@code (真实执行根, 所需环境)} 聚合的一组待领取 TOOL 调用的只读投影。它没有可操作的调用
 *       主键、状态或审批事实，只有 {@code environmentId} / {@code environmentName} / {@code
 *       waitingCount}；排序/游标代表是最早未完成调用，但绝不冒充可操作的 interaction。
 * </ul>
 *
 * <p>与 Pane 读取同一事实源：人工等待条目携带 {@code threadId} / {@code sessionId} 的原始调用坐标与冻结的
 * ToolCall；环境等待条目只保留聚合身份。{@code owner} 提供 Pane 跳转所需的产品归属；{@code rootThreadId}
 * 始终指向该来源所属执行树的真实根，供根面板与全局交互中心按根过滤和展示。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = InteractionDTO.Input.class, name = "INPUT"),
  @JsonSubTypes.Type(value = InteractionDTO.Approval.class, name = "APPROVAL"),
  @JsonSubTypes.Type(value = InteractionDTO.EnvironmentWait.class, name = "ENVIRONMENT_WAIT")
})
public sealed interface InteractionDTO
    permits InteractionDTO.Input, InteractionDTO.Approval, InteractionDTO.EnvironmentWait {

  /** 来源所属执行树的真实根 Thread：后代来源也指向同一根，永远非空。 */
  String rootThreadId();

  /** 服务端解析的产品归属或独立 Agent 任务。 */
  InteractionOwnerDTO owner();

  /** 人工等待为原始调用创建时间；环境等待为组内最早调用的创建时间（排序/游标代表）。 */
  Instant createdAt();

  /** 等待人工输入的 ToolInvocation。 */
  record Input(
      String toolInvocationId,
      String threadId,
      String sessionId,
      String rootThreadId,
      InteractionOwnerDTO owner,
      String toolCallId,
      String toolName,
      String argumentsJson,
      Instant createdAt)
      implements InteractionDTO {}

  /** 等待审批的 ToolInvocation；{@code approvalJson} 是冻结的审批载荷。 */
  record Approval(
      String toolInvocationId,
      String threadId,
      String sessionId,
      String rootThreadId,
      InteractionOwnerDTO owner,
      String toolCallId,
      String toolName,
      String argumentsJson,
      @Nullable String approvalJson,
      Instant createdAt)
      implements InteractionDTO {}

  /** 按 {@code (真实执行根, 所需环境)} 聚合的只读环境等待。 */
  record EnvironmentWait(
      String rootThreadId,
      InteractionOwnerDTO owner,
      String environmentId,
      String environmentName,
      int waitingCount,
      Instant createdAt)
      implements InteractionDTO {}
}
