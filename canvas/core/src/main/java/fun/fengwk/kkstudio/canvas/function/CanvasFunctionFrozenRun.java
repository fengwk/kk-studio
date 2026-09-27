package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter 每次执行或恢复时读取的完整冻结计划与 checkpoint。
 *
 * <p>{@code args} 是启动时按函数 schema 校验并补齐默认值后的 canonical 输入，{@code manifest} 是启动时冻结的输入资源事实，两者在执行期间都不再随
 * 节点配置变化。{@code submitState} 是 Runtime 持久化的外部提交事实：只有 {@link CanvasFunctionSubmitState#PENDING}
 * 才允许发起外部提交， {@link CanvasFunctionSubmitState#SUBMITTING} 表示提交意图已持久化但结果不明，{@link
 * CanvasFunctionSubmitState#SUBMITTED} 表示外部任务身份 已可查询，恢复时只能查询而不能重新提交。
 */
public record CanvasFunctionFrozenRun(
    UUID canvasId,
    UUID nodeId,
    String nodeName,
    UUID requestId,
    CanvasFunctionDefinition definition,
    JsonObject args,
    List<CanvasFunctionFrozenReference> manifest,
    String outputName,
    UUID targetResourceId,
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
    requireText(outputName, "outputName");
    Objects.requireNonNull(targetResourceId, "targetResourceId");
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

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
