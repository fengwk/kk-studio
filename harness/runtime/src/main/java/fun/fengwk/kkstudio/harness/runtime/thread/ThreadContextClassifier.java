package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 纯 Thread live/historical 适用性分类器：不接触 Store、不产生锁、无副作用。
 *
 * <p>输入契约：{@code model} 只能是按 {@code (threadId, 当前 open turn 的 TURN_START entry id)} 精确查到的
 * ModelInvocation（无则 null）；{@code toolSiblings} 只能在该 Model 的 resultEntryId 等于当前 head 且 head 为
 * ASSISTANT Message 时加载（否则必须为空）。违反输入契约（不兼容形状）与任何持久化不变量破坏一样抛 {@link
 * IllegalStateException}，绝不把破坏状态降级为业务 kind。
 *
 * <p>精确分类规则：
 *
 * <ol>
 *   <li>无 open Turn：head 为 {@code continueModel=true} 的 TURN_END 时 {@code CONTINUATION_DUE}，否则
 *       {@code IDLE_OR_HISTORICAL}；此时非空 model/tools 输入是不变量错误。
 *   <li>有 open Turn 但本 Thread 无 Model：{@code IDLE_OR_HISTORICAL}（覆盖另一 Thread 共享历史 Turn 与 B′ 不恢复语义）。
 *   <li>Model 身份必须匹配当前 Thread 与 open TURN_START（threadId + turnStartEntryId 精确一致）。
 *   <li>{@code head == basisHeadEntryId && resultEntryId == null}：非 terminal 为 {@code
 *       MODEL_ACTIVE}， terminal 为 {@code MODEL_TERMINAL_PENDING}。
 *   <li>仅当 {@code model.resultEntryId == head}、head 为 ASSISTANT Message、Model SUCCEEDED 且携带 result、
 *       response toolCalls 与 Assistant ToolCall contents 按序逐字段一致、sibling 数量等于 calls、ordinal 为
 *       0..N-1 连续前缀且全部归本 Model 所有时进入 Tool context；无 calls 且无 siblings 为历史 no-tools Assistant
 *       前缀（{@code IDLE_OR_HISTORICAL}）；存在非 terminal sibling 为 {@code TOOL_ACTIVE}；全部 terminal 为
 *       {@code TOOL_TERMINAL_PENDING}（Tool outcome 尚未物化，batch apply 后行会被删除，不存在“已挂载”的历史状态）。
 *   <li>任何其他 head/basis/result 关系均为 {@code IDLE_OR_HISTORICAL}：不检查 descendant 结果，也不使用另一 Thread 的
 *       Model。
 * </ol>
 */
public final class ThreadContextClassifier {

  /**
   * 基于当前 Thread、root-to-head EntryPath、按 {@code (threadId, open turn)} 查到的 Model 与可选 Tool siblings
   * 计算当前 live/historical 适用性。输入必须满足 {@link ThreadContextClassifier} 的契约，任何不兼容形状抛 {@link
   * IllegalStateException}。
   */
  public ThreadContext classify(
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      List<ToolInvocation> toolSiblings) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(toolSiblings, "toolSiblings");
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isEmpty()) {
      // 规则 1：无 open Turn 时不允许携带任何 Model/Tool 输入。
      requireNoModelOrSiblings(model, toolSiblings, "without an open turn");
      Entry head = path.head();
      if (head.payload() instanceof TurnEndPayload end && end.continueModel()) {
        return new ThreadContext.ContinuationDue(head);
      }
      return new ThreadContext.IdleOrHistorical();
    }
    Entry turn = openTurn.get();
    if (model == null) {
      // 规则 2：另一 Thread 共享历史 TURN_START/Assistant 时本 Thread 无自己的 Model，绝不借他人 Model 推进。
      requireEmptySiblings(toolSiblings, "without a model");
      return new ThreadContext.IdleOrHistorical();
    }
    // 规则 3：Model 身份必须与当前 Thread + open TURN_START 精确一致。
    if (!model.threadId().equals(thread.id()) || !model.turnStartEntryId().equals(turn.id())) {
      throw new IllegalStateException(
          "model "
              + model.id()
              + " must belong to thread "
              + thread.id()
              + " and open turn start "
              + turn.id());
    }
    Entry head = path.head();
    if (head.id().equals(model.basisHeadEntryId()) && model.resultEntryId() == null) {
      // 规则 4：head 恰为 basis 且结果未挂载；siblings 只可能在结果 head 加载，出现在这里是不兼容形状。
      requireEmptySiblings(toolSiblings, "at the model basis");
      if (model.status().isTerminal()) {
        return new ThreadContext.ModelTerminalPending(model);
      }
      return new ThreadContext.ModelActive(model);
    }
    if (model.resultEntryId() == null || !model.resultEntryId().equals(head.id())) {
      // 规则 6：head/basis/result 的任何其他关系（含 relocation 回 basis、descendant 或另一分支）都是历史。
      requireEmptySiblings(toolSiblings, "without the model result at the head");
      return new ThreadContext.IdleOrHistorical();
    }
    // model.resultEntryId == head：只有 head 为 ASSISTANT Message 时才可能构成 Tool context。
    if (!(head.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      requireEmptySiblings(toolSiblings, "on a non-assistant result head");
      return new ThreadContext.IdleOrHistorical();
    }
    return toolContext(model, head, message, toolSiblings);
  }

  /** 规则 5：仅在 Model 结果恰好位于当前 ASSISTANT head 时对 Tool siblings 做完整一致性校验与状态分类。 */
  private ThreadContext toolContext(
      ModelInvocation model,
      Entry assistant,
      MessagePayload message,
      List<ToolInvocation> siblings) {
    if (model.status() != ModelInvocationStatus.SUCCEEDED) {
      throw new IllegalStateException(
          "model "
              + model.id()
              + " with status "
              + model.status()
              + " must not attach an assistant message entry "
              + assistant.id());
    }
    if (model.result() == null) {
      throw new IllegalStateException(
          "succeeded model "
              + model.id()
              + " must carry a result for assistant entry "
              + assistant.id());
    }
    List<ToolCallMessageContent> calls = toolCalls(message);
    if (!responseToolCallsMatch(model.result(), calls)) {
      throw new IllegalStateException(
          "model "
              + model.id()
              + " result tool calls must match the assistant message tool calls of entry "
              + assistant.id());
    }
    if (calls.isEmpty() && siblings.isEmpty()) {
      // 合法历史：assistant 无 tool call，交 normalization。
      return new ThreadContext.IdleOrHistorical();
    }
    if (calls.size() != siblings.size()) {
      throw new IllegalStateException(
          "tool sibling count "
              + siblings.size()
              + " must match the assistant tool calls "
              + calls.size()
              + " of entry "
              + assistant.id());
    }
    for (int i = 0; i < siblings.size(); i++) {
      ToolInvocation sibling = siblings.get(i);
      if (sibling.ordinal() != i) {
        throw new IllegalStateException(
            "tool siblings must be a contiguous ordinal prefix of entry " + assistant.id());
      }
      if (!sibling.modelInvocationId().equals(model.id())) {
        throw new IllegalStateException(
            "tool siblings of entry " + assistant.id() + " must be owned by model " + model.id());
      }
    }
    // Tool siblings 存在即 outcome 尚未物化：任一非 terminal 是 blocker，全部 terminal 待 batch apply。
    boolean allTerminal = true;
    for (ToolInvocation sibling : siblings) {
      if (!sibling.status().isTerminal()) {
        allTerminal = false;
        break;
      }
    }
    if (!allTerminal) {
      return new ThreadContext.ToolActive(model, assistant, calls, siblings);
    }
    return new ThreadContext.ToolTerminalPending(model, assistant, calls, siblings);
  }

  private static void requireNoModelOrSiblings(
      ModelInvocation model, List<ToolInvocation> toolSiblings, String context) {
    if (model != null) {
      throw new IllegalStateException("a model is incompatible " + context);
    }
    requireEmptySiblings(toolSiblings, context);
  }

  private static void requireEmptySiblings(List<ToolInvocation> toolSiblings, String context) {
    if (!toolSiblings.isEmpty()) {
      throw new IllegalStateException("tool siblings are incompatible " + context);
    }
  }

  /** 按序提取 assistant Message 中的 ToolCall contents。 */
  private static List<ToolCallMessageContent> toolCalls(MessagePayload message) {
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (var content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    return calls;
  }

  /** response toolCalls 与 assistant Message ToolCall contents 按序逐字段一致（id/name/argumentsJson）。 */
  private static boolean responseToolCallsMatch(
      ProviderResponse response, List<ToolCallMessageContent> calls) {
    List<ProviderToolCall> responseCalls = response.toolCalls();
    if (responseCalls.size() != calls.size()) {
      return false;
    }
    for (int i = 0; i < calls.size(); i++) {
      ProviderToolCall call = responseCalls.get(i);
      ToolCallMessageContent content = calls.get(i);
      if (!call.id().equals(content.toolCallId())
          || !call.name().equals(content.toolName())
          || !call.argumentsJson().equals(content.argumentsJson())) {
        return false;
      }
    }
    return true;
  }
}
