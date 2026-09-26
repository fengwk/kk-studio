package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ResourceNode 上可选的资源生产配置：函数名 + 插件声明的 args。
 *
 * <p>args 是 {@link CanvasJson.JsonObject}，因此 {@link Object#equals(Object)} 就是 JSON 语义比较，可直接作为
 * Function 语义组的编辑基线。 核心按保留引用形状遍历 args：出现 {@code type=resource} 的对象必须恰好声明 {@code
 * type/nodeId/index}，否则视为非法配置。
 */
public record CanvasFunction(String name, CanvasJson.JsonObject args) {

  /** 单个节点 args 允许声明的不同引用数量上限。 */
  public static final int MAX_REFERENCES = 32;

  private static final String REFERENCE_TYPE = "resource";
  private static final Set<String> REFERENCE_FIELDS = Set.of("type", "nodeId", "index");

  public CanvasFunction {
    name = CanvasValidation.requireName(name, "function name");
    Objects.requireNonNull(args, "args");
  }

  /** args 的压缩 JSON 文本，用于持久化与 API 表达。 */
  public String argsJson() {
    return args.write();
  }

  /**
   * 按首次出现顺序返回去重后的引用；出现非法引用形状时抛 {@link CanvasValidationException}。
   *
   * <p>即使函数插件未安装，也能从已有配置中识别引用，从而展示连线并在删除源节点前要求显式解除引用。
   */
  public List<CanvasResourceReference> references() {
    Set<CanvasResourceReference> references = new LinkedHashSet<>();
    collect(args, references, 0);
    if (references.size() > MAX_REFERENCES) {
      throw new CanvasValidationException(
          "function args must not declare more than " + MAX_REFERENCES + " references");
    }
    return List.copyOf(references);
  }

  private static void collect(
      CanvasJson value, Set<CanvasResourceReference> references, int depth) {
    if (depth > CanvasJson.MAX_DEPTH) {
      throw new CanvasValidationException(
          "function args nesting must not exceed " + CanvasJson.MAX_DEPTH);
    }
    switch (value) {
      case JsonObject object -> {
        if (isReferenceCandidate(object)) {
          references.add(parseReference(object));
          return;
        }
        for (CanvasJson item : object.values().values()) {
          collect(item, references, depth + 1);
        }
      }
      case JsonArray array -> {
        for (CanvasJson item : array.values()) {
          collect(item, references, depth + 1);
        }
      }
      case JsonText ignored -> {}
      case JsonNumber ignored -> {}
      case CanvasJson.JsonBool ignored -> {}
      case CanvasJson.JsonNull ignored -> {}
    }
  }

  private static boolean isReferenceCandidate(JsonObject object) {
    CanvasJson type = object.values().get("type");
    return type instanceof JsonText text && REFERENCE_TYPE.equals(text.value());
  }

  private static CanvasResourceReference parseReference(JsonObject object) {
    if (!object.values().keySet().equals(REFERENCE_FIELDS)) {
      throw new CanvasValidationException(
          "resource reference must declare exactly type/nodeId/index");
    }
    CanvasJson nodeId = object.values().get("nodeId");
    if (!(nodeId instanceof JsonText text)) {
      throw new CanvasValidationException("resource reference nodeId must be a UUID string");
    }
    UUID parsedNodeId;
    try {
      parsedNodeId = UUID.fromString(text.value());
    } catch (IllegalArgumentException error) {
      throw new CanvasValidationException("resource reference nodeId must be a UUID string");
    }
    CanvasJson index = object.values().get("index");
    if (!(index instanceof JsonNumber number)) {
      throw new CanvasValidationException("resource reference index must be an integer");
    }
    int parsedIndex;
    try {
      parsedIndex = number.value().intValueExact();
    } catch (ArithmeticException error) {
      throw new CanvasValidationException("resource reference index must be an integer");
    }
    if (parsedIndex < 0) {
      throw new CanvasValidationException("resource reference index must be nonnegative");
    }
    return new CanvasResourceReference(parsedNodeId, parsedIndex);
  }
}
