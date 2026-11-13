package fun.fengwk.kkstudio.harness.contributor.builtin.compact;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.compaction.CompactToolName;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentPrompts;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 内置运行时工具 {@code compact}：模型在自然断点请求整理上下文。
 *
 * <p>它不是可执行动作，而是一次排队请求：Runtime 在工具批次结束后按手动压缩规则启动 COMPACTION 回合，工具本身只返回固定的“已排队”结果， 不触发压缩、不访问
 * Store、也不产生任何 I/O。COMPACTION Thread 内该工具与其它平台工具一样被禁用，绝不递归压缩。
 */
public final class CompactTool implements Tool {

  /** 模型可见工具名；Runtime 按 durable ToolResult 的该名称识别排队请求。 */
  public static final String NAME = CompactToolName.NAME;

  private static final InputSchema INPUT_SCHEMA =
      new InputSchema("No parameters.", Map.of(), Set.of(), false);

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          EnvironmentPrompts.load("compact.md"),
          NAME,
          INPUT_SCHEMA,
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  /** 只确认请求已排队：压缩回合由 Runtime 在批次结束后的下一个安全边界启动。 */
  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    Objects.requireNonNull(request, "request");
    return new ToolOutcome(
        new ToolResult(
            request.call().id(),
            List.of(
                new TextResultContent(
                    "Context compaction is queued. It runs after this tool batch; the next"
                        + " request continues from the compacted summary.")),
            false,
            "{}"));
  }
}
