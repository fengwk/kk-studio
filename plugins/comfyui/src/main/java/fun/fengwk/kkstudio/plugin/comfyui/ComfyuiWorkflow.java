package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个已解析并经校验的冻结工作流定义。
 *
 * <p>图、参数/媒体绑定、输出 selector 与引用策略都是配置唯一来源的不可变快照：每个配置修订产出一份全新的 {@code ComfyuiWorkflow}，因此热更新不会改变已冻结
 * Run 的解释。图只在构造时深拷贝一次，并且只经 {@link #graph()} 以副本暴露，调用方无法改写快照。
 */
public record ComfyuiWorkflow(
    String id,
    String description,
    Strategy strategy,
    ObjectNode graph,
    JsonObject argsSchema,
    List<ParameterBinding> parameterBindings,
    List<MediaBinding> mediaBindings,
    List<OutputSelector> outputs,
    ReferencePolicy referencePolicy) {

  /** 冻结工作流的执行策略：{@code DIRECT} 直接注入绑定，{@code H3} 保留 MiniMax-H3 Ref2VA 专属语义。 */
  public enum Strategy {
    DIRECT,
    H3
  }

  /** ComfyUI history 输出数组的字段名，同时决定 selector 的媒体类型。 */
  public enum OutputField {
    IMAGES("images", CanvasResourceKind.IMAGE),
    VIDEOS("videos", CanvasResourceKind.VIDEO),
    AUDIO("audio", CanvasResourceKind.AUDIO);

    private final String jsonName;
    private final CanvasResourceKind kind;

    OutputField(String jsonName, CanvasResourceKind kind) {
      this.jsonName = jsonName;
      this.kind = kind;
    }

    public String jsonName() {
      return jsonName;
    }

    public CanvasResourceKind kind() {
      return kind;
    }

    public static OutputField fromJson(String value) {
      for (OutputField field : values()) {
        if (field.jsonName.equals(value)) {
          return field;
        }
      }
      throw new IllegalArgumentException("unsupported output field: " + value);
    }
  }

  /** args 顶层参数到图输入的绑定：只有 argsSchema 已声明的参数才能写入已存在的 node/input。 */
  public record ParameterBinding(String argument, String nodeId, String input) {

    public ParameterBinding {
      argument = requireText(argument, "parameterBindings.argument");
      nodeId = requireText(nodeId, "parameterBindings.nodeId");
      input = requireText(input, "parameterBindings.input");
    }
  }

  /** frozen manifest 顺序索引到图输入的绑定：索引唯一连续 0..N-1，且上传产物正好填满这些输入。 */
  public record MediaBinding(int referenceIndex, String nodeId, String input) {

    public MediaBinding {
      if (referenceIndex < 0) {
        throw new IllegalArgumentException("mediaBindings.referenceIndex must be nonnegative");
      }
      nodeId = requireText(nodeId, "mediaBindings.nodeId");
      input = requireText(input, "mediaBindings.input");
    }
  }

  /** 冻结定义持有的输出 selector：{@code name} 为 null 表示单一匿名槽。 */
  public record OutputSelector(
      String name, CanvasResourceKind kind, String nodeId, OutputField field, int index) {

    public OutputSelector {
      if (name != null) {
        name = requireText(name, "outputs.name");
      }
      kind = Objects.requireNonNull(kind, "outputs.kind");
      nodeId = requireText(nodeId, "outputs.nodeId");
      field = Objects.requireNonNull(field, "outputs.field");
      if (index < 0) {
        throw new IllegalArgumentException("outputs.index must be nonnegative");
      }
      if (field.kind() != kind) {
        throw new IllegalArgumentException(
            "outputs.field " + field.jsonName() + " does not match kind " + kind);
      }
    }
  }

  /** 工作流自己的引用策略，直接映射为 {@link CanvasFunctionReferencePolicy}。 */
  public record ReferencePolicy(
      Set<CanvasResourceKind> allowedKinds,
      int maxCount,
      Map<CanvasResourceKind, Integer> maxCountByKind) {

    public ReferencePolicy {
      allowedKinds = Set.copyOf(Objects.requireNonNull(allowedKinds, "allowedKinds"));
      maxCountByKind = Map.copyOf(Objects.requireNonNull(maxCountByKind, "maxCountByKind"));
    }

    public CanvasFunctionReferencePolicy toCanvasPolicy() {
      return new CanvasFunctionReferencePolicy(allowedKinds, maxCount, maxCountByKind);
    }
  }

  public ComfyuiWorkflow {
    id = requireText(id, "id");
    description = requireText(description, "description");
    strategy = Objects.requireNonNull(strategy, "strategy");
    graph = Objects.requireNonNull(graph, "graph").deepCopy();
    argsSchema = Objects.requireNonNull(argsSchema, "argsSchema");
    parameterBindings = List.copyOf(Objects.requireNonNull(parameterBindings, "parameterBindings"));
    mediaBindings = List.copyOf(Objects.requireNonNull(mediaBindings, "mediaBindings"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    referencePolicy = Objects.requireNonNull(referencePolicy, "referencePolicy");
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("workflow " + id + " must declare at least one output");
    }
    if (outputs.size() > 1) {
      for (OutputSelector selector : outputs) {
        if (selector.name() == null) {
          throw new IllegalArgumentException(
              "workflow " + id + " must name every output slot when it declares multiple outputs");
        }
      }
    }
    Set<String> arguments = new HashSet<>();
    for (ParameterBinding binding : parameterBindings) {
      if (!arguments.add(binding.argument())) {
        throw new IllegalArgumentException(
            "workflow " + id + " must not bind the same argument twice: " + binding.argument());
      }
    }
    Set<String> targets = new HashSet<>();
    for (ParameterBinding binding : parameterBindings) {
      requireUniqueTarget(targets, binding.nodeId(), binding.input());
    }
    for (MediaBinding binding : mediaBindings) {
      requireUniqueTarget(targets, binding.nodeId(), binding.input());
    }
    Set<Integer> indexes = new HashSet<>();
    for (MediaBinding binding : mediaBindings) {
      if (!indexes.add(binding.referenceIndex())) {
        throw new IllegalArgumentException(
            "workflow "
                + id
                + " must not repeat a media reference index: "
                + binding.referenceIndex());
      }
    }
    for (int index = 0; index < mediaBindings.size(); index++) {
      if (!indexes.contains(index)) {
        throw new IllegalArgumentException(
            "workflow "
                + id
                + " media reference indexes must be exactly 0.."
                + (indexes.size() - 1));
      }
    }
    Set<String> slots = new HashSet<>();
    for (OutputSelector selector : outputs) {
      String slot = selector.nodeId() + "#" + selector.field().jsonName() + "#" + selector.index();
      if (!slots.add(slot)) {
        throw new IllegalArgumentException("workflow " + id + " must not repeat an output slot");
      }
    }
  }

  /** 该工作流冻结的 Canvas Function 定义；每次调用都从同一不可变快照派生。 */
  public CanvasFunctionDefinition definition() {
    List<CanvasFunctionOutputSpec> specs =
        outputs.stream()
            .map(
                selector ->
                    selector.name() == null
                        ? CanvasFunctionOutputSpec.of(selector.kind())
                        : CanvasFunctionOutputSpec.named(selector.kind(), selector.name()))
            .toList();
    return new CanvasFunctionDefinition(
        id, description, argsSchema, specs, referencePolicy.toCanvasPolicy());
  }

  /** 冻结图的调用方副本：每次访问都返回独立深拷贝，任何原地改写都不影响快照与后续构图。 */
  @Override
  public ObjectNode graph() {
    return graph.deepCopy();
  }

  private static void requireUniqueTarget(Set<String> targets, String nodeId, String input) {
    if (!targets.add(nodeId + "#" + input)) {
      throw new IllegalArgumentException(
          "workflow bindings must not write the same node/input twice: " + nodeId + "." + input);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value;
  }
}
