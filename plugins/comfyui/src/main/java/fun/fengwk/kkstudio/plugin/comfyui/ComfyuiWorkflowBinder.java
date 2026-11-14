package fun.fengwk.kkstudio.plugin.comfyui;

import static fun.fengwk.kkstudio.canvas.function.CanvasArguments.extractText;

import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;

/**
 * 冻结工作流的一次性组装与参数校验：{@code DIRECT} 按绑定注入参数与媒体，{@code H3} 交给预设构造器。
 *
 * <p>校验只做纯值解析：已知的参数错误必须在任何上传/Prompt/远端提交之前暴露，因此这里不产生任何副作用。
 */
final class ComfyuiWorkflowBinder {

  private final ComfyuiWorkflow workflow;
  private final H3MediaPreflight mediaPreflight;

  ComfyuiWorkflowBinder(ComfyuiWorkflow workflow, H3MediaPreflight mediaPreflight) {
    this.workflow = workflow;
    this.mediaPreflight = mediaPreflight;
  }

  void preflight(CanvasFunctionFrozenRun run) {
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
      String prompt = extractText(run.args(), "prompt");
      if (prompt == null || prompt.isBlank()) {
        throw new IllegalArgumentException("H3 prompt must not be blank");
      }
      mediaPreflight.validate(run.manifest());
      return;
    }
    // DIRECT：已知的参数错误必须在任何上传/Prompt/远端提交之前暴露，因此这里只做纯值解析（显式值或 schema 默认）。
    for (ComfyuiWorkflow.ParameterBinding binding : workflow.parameterBindings()) {
      argument(run.args(), binding.argument());
    }
    // DIRECT 媒体绑定按 manifest 顺序固定索引：绑定的数量必须正好覆盖全部 frozen 引用（0 引用合法）。
    if (run.manifest().size() != workflow.mediaBindings().size()) {
      throw new IllegalArgumentException(
          "workflow "
              + workflow.id()
              + " requires exactly "
              + workflow.mediaBindings().size()
              + " media references");
    }
  }

  /** 冻结图的一次性副本：DIRECT 按绑定注入参数与媒体，H3 由预设构造器注入增强提示词与动态媒体节点。 */
  ObjectNode bind(CanvasFunctionFrozenRun run, H3ReferenceManifest manifest, H3AdapterState state) {
    ObjectNode graph = workflow.graph();
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
      ComfyuiWorkflow.OutputSelector selector = workflow.outputs().get(0);
      return H3WorkflowBuilder.build(
          graph,
          selector.nodeId(),
          ComfyuiTexts.requireText(state.enhancedPrompt(), "enhancedPrompt"),
          extractText(run.args(), "ratio", "16:9"),
          extractInt(run.args(), "duration", 5),
          requireNonnegative(state.seed(), "seed"),
          run.output(0).resourceId(),
          manifest,
          state.uploads());
    }
    writeParameterBindings(graph, run);
    writeMediaBindings(graph, manifest, state);
    return graph;
  }

  private void writeParameterBindings(ObjectNode graph, CanvasFunctionFrozenRun run) {
    for (ComfyuiWorkflow.ParameterBinding binding : workflow.parameterBindings()) {
      ComfyuiGraph.write(
          graph,
          binding.nodeId(),
          binding.input(),
          ComfyuiGraph.toJson(argument(run.args(), binding.argument())));
    }
  }

  private void writeMediaBindings(
      ObjectNode graph, H3ReferenceManifest manifest, H3AdapterState state) {
    for (ComfyuiWorkflow.MediaBinding binding : workflow.mediaBindings()) {
      CanvasFunctionFrozenReference reference =
          manifest.items().get(binding.referenceIndex()).reference();
      H3UploadedFile uploaded = state.uploads().get(reference.resourceId());
      if (uploaded == null) {
        throw new IllegalArgumentException("missing ComfyUI upload for a bound media reference");
      }
      ComfyuiGraph.write(
          graph, binding.nodeId(), binding.input(), ComfyuiGraph.textNode(uploaded.path()));
    }
  }

  /** 已注册 schema 的合法 args：显式值优先，缺失时回退到 argsSchema 声明的默认值。 */
  private CanvasJson argument(JsonObject args, String name) {
    CanvasJson value = args.values().get(name);
    if (value != null) {
      return value;
    }
    CanvasJson defaultValue = declaredDefault(name);
    if (defaultValue != null) {
      return defaultValue;
    }
    throw new IllegalArgumentException("workflow argument is missing: " + name);
  }

  private CanvasJson declaredDefault(String name) {
    if (!(workflow.argsSchema().values().get("properties") instanceof JsonObject properties)) {
      return null;
    }
    if (!(properties.values().get(name) instanceof JsonObject property)) {
      return null;
    }
    return property.values().get("default");
  }

  private static int extractInt(JsonObject args, String field, int defaultValue) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonNumber number) {
      return number.value().intValueExact();
    }
    if (json == null) {
      return defaultValue;
    }
    throw new IllegalArgumentException(field + " must be an integer");
  }

  private static long requireNonnegative(Long value, String field) {
    if (value == null || value < 0L) {
      throw new IllegalArgumentException(field + " must be nonnegative");
    }
    return value;
  }
}
