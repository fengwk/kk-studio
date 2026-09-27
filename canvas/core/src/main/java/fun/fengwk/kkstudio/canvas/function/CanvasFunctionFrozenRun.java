package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter 每次执行或恢复时读取的完整冻结计划与 checkpoint。
 *
 * <p>{@code args} 是启动时按函数 schema 校验并补齐默认值后的 canonical 输入，{@code manifest} 是启动时冻结的输入资源事实，两者在执行期间都不再随
 * 节点配置变化。{@code outputs} 是启动时冻结的有界输出计划：每个槽位已预分配 Resource ID、节点内位置、类型与资源名，adapter 只能物化计划内槽位。 {@code
 * submitState} 是 Runtime 持久化的外部提交事实：只有 {@link CanvasFunctionSubmitState#PENDING} 才允许发起外部提交， {@link
 * CanvasFunctionSubmitState#SUBMITTING} 表示提交意图已持久化但结果不明，{@link CanvasFunctionSubmitState#SUBMITTED}
 * 表示外部任务身份 已可查询，恢复时只能查询而不能重新提交。
 */
public record CanvasFunctionFrozenRun(
    UUID canvasId,
    UUID nodeId,
    String nodeName,
    UUID requestId,
    CanvasFunctionDefinition definition,
    JsonObject args,
    List<CanvasFunctionFrozenReference> manifest,
    List<CanvasFunctionFrozenOutput> outputs,
    CanvasFunctionSubmitState submitState,
    String stage,
    Map<String, Object> adapterState) {

  public CanvasFunctionFrozenRun {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    requireText(nodeName, "nodeName");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(args, "args");
    manifest = List.copyOf(Objects.requireNonNull(manifest, "manifest"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    if (outputs.size() != definition.outputs().size()) {
      throw new IllegalArgumentException("outputs must match the frozen definition plan");
    }
    Set<String> names = new LinkedHashSet<>();
    Set<UUID> resourceIds = new LinkedHashSet<>();
    for (int index = 0; index < outputs.size(); index++) {
      CanvasFunctionFrozenOutput output = Objects.requireNonNull(outputs.get(index), "output");
      CanvasFunctionOutputSpec spec = definition.outputs().get(index);
      if (output.index() != index || output.kind() != spec.kind()) {
        throw new IllegalArgumentException("output slot must match the definition plan");
      }
      if (!names.add(output.name())) {
        throw new IllegalArgumentException("output names must be unique within a run");
      }
      if (!resourceIds.add(output.resourceId())) {
        throw new IllegalArgumentException("output resource ids must be unique within a run");
      }
    }
    Objects.requireNonNull(submitState, "submitState");
    requireText(stage, "stage");
    adapterState =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(adapterState, "adapterState")));
  }

  /** 是否已经持久化可查询的外部任务身份；为真时 adapter 只能查询，不得重新提交。 */
  public boolean submitted() {
    return submitState == CanvasFunctionSubmitState.SUBMITTED;
  }

  /** 冻结输出计划中的全部预分配 Resource ID，按槽位顺序。 */
  public List<UUID> outputResourceIds() {
    return outputs.stream().map(CanvasFunctionFrozenOutput::resourceId).toList();
  }

  /** 按槽位取冻结输出；越界即拒绝。 */
  public CanvasFunctionFrozenOutput output(int index) {
    if (index < 0 || index >= outputs.size()) {
      throw new IllegalArgumentException("output index is outside the frozen output plan");
    }
    return outputs.get(index);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
