package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AgentRunContext 表示一次主链运行中的可变状态。
 *
 * <p>被 Agent 状态机、assistant runner、tool orchestrator 共享，避免反向依赖。
 *
 * @author fengwk
 */
final class AgentRunContext {

  /** 当前 loop 中已经发生的模型重试次数。 */
  int retryCount;

  /** 当前 loop 是否已进入显式取消流程。 */
  boolean aborted;

  /** 当前已注册但尚未触发的重试调度任务。 */
  ScheduledTask scheduledTask;

  /** 当前活跃的 assistant 尝试状态（null 表示等待 retry delay 或无活动）。 */
  AssistantAttemptState activeAssistant;

  /** 当前 loop 中全部 toolCallId 对应的执行状态。 */
  final Map<String, ToolExecutionState> toolStates = new LinkedHashMap<>();
}

/** AssistantAttemptState 表示一次 assistant 尝试的流式累加状态。 */
final class AssistantAttemptState {

  final StringBuilder text = new StringBuilder();
  final StringBuilder thinking = new StringBuilder();
  final TreeMap<Integer, ToolCallState> toolCalls = new TreeMap<>();
  AssistantResponseHandle handle;

  /** 应用一条 tool call 增量到当前 assistant 累加状态。 */
  void applyToolCallDelta(IndexedToolCallDelta indexedToolCallDelta) {
    if (indexedToolCallDelta == null
        || indexedToolCallDelta.getIndex() == null
        || indexedToolCallDelta.getIndex() < 0
        || indexedToolCallDelta.getToolCallDelta() == null) {
      return;
    }
    ToolCallState toolCallState =
        toolCalls.computeIfAbsent(indexedToolCallDelta.getIndex(), key -> new ToolCallState());
    ToolCallDelta toolCallDelta = indexedToolCallDelta.getToolCallDelta();
    if (toolCallDelta.getToolCallId() != null) {
      toolCallState.toolCallId = toolCallDelta.getToolCallId();
    }
    if (toolCallDelta.getToolName() != null) {
      toolCallState.toolName = toolCallDelta.getToolName();
    }
    if (toolCallDelta.getArgumentsDelta() != null) {
      toolCallState.arguments.append(toolCallDelta.getArgumentsDelta());
    }
  }

  List<IndexedToolCallDelta> computeToolCallGap(Integer index, ToolCall toolCall) {
    if (index == null || index < 0 || toolCall == null) {
      return List.of();
    }
    ToolCallState toolCallState = toolCalls.computeIfAbsent(index, key -> new ToolCallState());
    ToolCallDelta toolCallDelta = new ToolCallDelta();
    if (toolCallState.toolCallId == null && toolCall.getToolCallId() != null) {
      toolCallState.toolCallId = toolCall.getToolCallId();
      toolCallDelta.setToolCallId(toolCall.getToolCallId());
    }
    if (toolCallState.toolName == null && toolCall.getToolName() != null) {
      toolCallState.toolName = toolCall.getToolName();
      toolCallDelta.setToolName(toolCall.getToolName());
    }
    String argumentsGap =
        AgentTextDelta.gap(toolCallState.arguments.toString(), toolCall.getArguments());
    if (argumentsGap != null && !argumentsGap.isEmpty()) {
      toolCallState.arguments.append(argumentsGap);
      toolCallDelta.setArgumentsDelta(argumentsGap);
    }
    if (toolCallDelta.getToolCallId() == null
        && toolCallDelta.getToolName() == null
        && toolCallDelta.getArgumentsDelta() == null) {
      return List.of();
    }
    IndexedToolCallDelta indexedToolCallDelta = new IndexedToolCallDelta();
    indexedToolCallDelta.setIndex(index);
    indexedToolCallDelta.setToolCallDelta(toolCallDelta);
    return List.of(indexedToolCallDelta);
  }

  List<IndexedToolCallDelta> computeToolCallGaps(List<ToolCall> finalToolCalls) {
    if (finalToolCalls == null || finalToolCalls.isEmpty()) {
      return List.of();
    }
    List<IndexedToolCallDelta> result = new ArrayList<>();
    for (int i = 0; i < finalToolCalls.size(); i++) {
      result.addAll(computeToolCallGap(i, finalToolCalls.get(i)));
    }
    return result;
  }
}

/** ToolCallState 表示 assistant 中单个 tool call 的累加状态。 */
final class ToolCallState {

  String toolCallId;
  String toolName;
  final StringBuilder arguments = new StringBuilder();
}

/** ToolExecutionState 表示单个 tool call 的执行与结果累加状态。 */
final class ToolExecutionState {

  final ToolCall toolCall;
  final List<ToolContentAccumulator> contents = new ArrayList<>();
  ToolExecutionHandle handle;
  boolean closed;

  ToolExecutionState(ToolCall toolCall) {
    this.toolCall = toolCall;
  }

  /** 应用一批 tool result 增量到当前执行状态。 */
  void applyContentDeltas(List<IndexedToolContentDelta> contentDeltas) {
    for (IndexedToolContentDelta indexedToolContentDelta : contentDeltas) {
      if (indexedToolContentDelta == null
          || indexedToolContentDelta.getIndex() == null
          || indexedToolContentDelta.getContentDelta() == null) {
        continue;
      }
      int index = indexedToolContentDelta.getIndex();
      if (index < 0) {
        continue;
      }
      while (contents.size() <= index) {
        contents.add(null);
      }
      ToolContentAccumulator accumulator = contents.get(index);
      ToolContentDelta contentDelta = indexedToolContentDelta.getContentDelta();
      if (contentDelta.getType() == null) {
        continue;
      }
      if (accumulator == null) {
        accumulator = new ToolContentAccumulator();
        accumulator.type = contentDelta.getType();
        contents.set(index, accumulator);
      }
      if (accumulator.type == ToolContentType.text
          && contentDelta.getType() == ToolContentType.text) {
        if (contentDelta.getText() != null) {
          accumulator.text.append(contentDelta.getText());
        }
      } else if (accumulator.type == contentDelta.getType()) {
        if (accumulator.data == null) {
          accumulator.data = contentDelta.getData();
        }
        if (accumulator.mime == null) {
          accumulator.mime = contentDelta.getMime();
        }
        if (accumulator.name == null) {
          accumulator.name = contentDelta.getName();
        }
      }
    }
  }

  List<IndexedToolContentDelta> computeGap(List<ToolContent> finalContents) {
    if (finalContents == null || finalContents.isEmpty()) {
      return List.of();
    }
    List<IndexedToolContentDelta> result = new ArrayList<>();
    for (int i = 0; i < finalContents.size(); i++) {
      ToolContent toolContent = finalContents.get(i);
      while (contents.size() <= i) {
        contents.add(null);
      }
      ToolContentAccumulator accumulator = contents.get(i);
      if (toolContent == null || toolContent.getType() == null) {
        continue;
      }
      if (accumulator == null) {
        accumulator = new ToolContentAccumulator();
        accumulator.type = toolContent.getType();
        contents.set(i, accumulator);
      }
      ToolContentDelta delta = new ToolContentDelta();
      delta.setType(toolContent.getType());
      if (toolContent.getType() == ToolContentType.text) {
        String gap = AgentTextDelta.gap(accumulator.text.toString(), toolContent.getText());
        if (gap == null || gap.isEmpty()) {
          continue;
        }
        accumulator.text.append(gap);
        delta.setText(gap);
      } else if (accumulator.data == null) {
        accumulator.data = toolContent.getData();
        accumulator.mime = toolContent.getMime();
        accumulator.name = toolContent.getName();
        delta.setData(toolContent.getData());
        delta.setMime(toolContent.getMime());
        delta.setName(toolContent.getName());
      } else {
        continue;
      }
      IndexedToolContentDelta indexedToolContentDelta = new IndexedToolContentDelta();
      indexedToolContentDelta.setIndex(i);
      indexedToolContentDelta.setContentDelta(delta);
      result.add(indexedToolContentDelta);
    }
    return result;
  }
}

/** ToolContentAccumulator 表示单个 tool result 槽位的累加内容。 */
final class ToolContentAccumulator {

  ToolContentType type;
  final StringBuilder text = new StringBuilder();
  String data;
  String mime;
  String name;
}

/** AgentTextDelta 提供统一的 suffix gap 计算。 */
final class AgentTextDelta {

  private AgentTextDelta() {}

  static String gap(String current, String complete) {
    if (complete == null || complete.isEmpty()) {
      return null;
    }
    String safeCurrent = current == null ? "" : current;
    if (complete.equals(safeCurrent)) {
      return null;
    }
    if (complete.startsWith(safeCurrent)) {
      return complete.substring(safeCurrent.length());
    }
    return complete;
  }
}
