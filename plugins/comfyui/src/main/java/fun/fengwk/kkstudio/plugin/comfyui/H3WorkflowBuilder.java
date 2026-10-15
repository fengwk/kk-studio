package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MiniMax-H3 Ref2VA 预设的唯一事实来源：默认 API 图、args schema、固有绑定与图构造。
 *
 * <p>预设图允许普通配置修订，但必须始终保留 H3 优化链与帧长表达式；选择的 SaveVideo 输出节点由预置 selector 声明，构图 filename_prefix
 * 与输出解释都使用同一个 节点 id，客户端不持有任何节点常量。
 */
final class H3WorkflowBuilder {

  static final String WORKFLOW_ID = "minimax-h3-ref2va";
  static final String DESCRIPTION = "MiniMax-H3 Ref2VA";

  static final String LENGTH_EXPRESSION =
      "max(5, round(a * 24)) + (5 - (max(5, round(a * 24)) % 17)) % 17";

  static final String ARGS_SCHEMA_JSON =
      """
      {
        "type": "object",
        "description": "MiniMax-H3 Ref2VA parameters",
        "additionalProperties": false,
        "properties": {
          "prompt": {
            "type": "string",
            "description": "Prompt text"
          },
          "ratio": {
            "type": "string",
            "description": "Aspect ratio",
            "enum": ["21:9", "16:9", "4:3", "1:1", "3:4", "9:16"],
            "default": "16:9"
          },
          "duration": {
            "type": "integer",
            "description": "Duration in seconds",
            "minimum": 4,
            "maximum": 15,
            "default": 5
          },
          "references": {
            "type": "array",
            "description": "Reference resources",
            "items": {
              "type": "resourceReference",
              "description": "Reference resource"
            },
            "minItems": 0,
            "maxItems": 12
          }
        },
        "required": ["prompt"]
      }
      """;

  private static final String DEFAULT_TEMPLATE_RESOURCE =
      "/fun/fengwk/kkstudio/plugin/comfyui/minimax-h3-ref2va-workflow.json";

  private static final String DEFAULT_SAVE_NODE = "92";
  private static final int DYNAMIC_NODE_START = 1000;
  private static final Map<String, String> RATIOS =
      Map.of(
          "21:9", "21:9 (Ultrawide)",
          "16:9", "16:9 (Widescreen)",
          "4:3", "4:3 (Standard)",
          "1:1", "1:1 (Square)",
          "3:4", "3:4 (Portrait Standard)",
          "9:16", "9:16 (Portrait Widescreen)");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private H3WorkflowBuilder() {}

  /** 默认预置图原文：只作为 schema 默认值，用户修订后的图存放在普通配置里。 */
  static String defaultTemplateJson() {
    try (InputStream stream =
        H3WorkflowBuilder.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("MiniMax-H3 workflow template resource is missing");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read MiniMax-H3 workflow template", error);
    }
  }

  /** 预设的固有参数绑定：只有 argsSchema 已声明的顶层参数，且这些输入在预置图中真实存在。 */
  static List<ComfyuiWorkflow.ParameterBinding> intrinsicParameterBindings() {
    return List.of(
        new ComfyuiWorkflow.ParameterBinding("prompt", "136", "prompt"),
        new ComfyuiWorkflow.ParameterBinding("ratio", "115", "aspect_ratio"),
        new ComfyuiWorkflow.ParameterBinding("duration", "132", "value"));
  }

  /** 预设的引用策略：IMAGE≤9、VIDEO≤3、AUDIO≤3，总数≤12。 */
  static ComfyuiWorkflow.ReferencePolicy referencePolicy() {
    return new ComfyuiWorkflow.ReferencePolicy(
        Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
        12,
        Map.of(
            CanvasResourceKind.IMAGE, 9, CanvasResourceKind.VIDEO, 3, CanvasResourceKind.AUDIO, 3));
  }

  /** 预设的默认输出 selector：单一匿名 VIDEO 槽，指向默认 SaveVideo 节点。 */
  static ComfyuiWorkflow.OutputSelector defaultOutput() {
    return new ComfyuiWorkflow.OutputSelector(
        null, CanvasResourceKind.VIDEO, DEFAULT_SAVE_NODE, ComfyuiWorkflow.OutputField.VIDEOS, 0);
  }

  /**
   * H3 策略的预置校验：能力 ID、args schema、引用策略与固有绑定必须与已支持的 H3 语义逐项一致，输出必须是已验证图上唯一的匿名 VIDEO 槽，图必须保留 H3
   * 优化链与帧长表达式。
   */
  static void validatePreset(ComfyuiWorkflow workflow) {
    Objects.requireNonNull(workflow, "workflow");
    if (!WORKFLOW_ID.equals(workflow.id())) {
      throw new IllegalArgumentException(
          "H3 strategy is reserved for the " + WORKFLOW_ID + " preset workflow");
    }
    if (!workflow.argsSchema().equals(CanvasJson.parseObject(ARGS_SCHEMA_JSON))) {
      throw new IllegalArgumentException(
          "workflow " + WORKFLOW_ID + " argsSchemaJson must match the supported H3 args schema");
    }
    if (!referencePolicy().equals(workflow.referencePolicy())) {
      throw new IllegalArgumentException(
          "workflow "
              + WORKFLOW_ID
              + " referencePolicy must match the supported H3 reference policy");
    }
    if (!Set.copyOf(intrinsicParameterBindings())
        .equals(Set.copyOf(workflow.parameterBindings()))) {
      throw new IllegalArgumentException(
          "workflow " + WORKFLOW_ID + " parameterBindings must match the supported H3 bindings");
    }
    if (!workflow.mediaBindings().isEmpty()) {
      throw new IllegalArgumentException(
          "workflow " + WORKFLOW_ID + " must not declare mediaBindings");
    }
    if (workflow.outputs().size() != 1) {
      throw new IllegalArgumentException(
          "workflow " + WORKFLOW_ID + " must declare exactly one output selector");
    }
    ComfyuiWorkflow.OutputSelector selector = workflow.outputs().get(0);
    if (selector.name() != null
        || selector.kind() != CanvasResourceKind.VIDEO
        || selector.field() != ComfyuiWorkflow.OutputField.VIDEOS
        || selector.index() != 0) {
      throw new IllegalArgumentException(
          "workflow " + WORKFLOW_ID + " must declare a single anonymous VIDEO output slot");
    }
    validateTemplate(workflow.graph(), selector.nodeId());
  }

  /** 图校验：样本节点必须已删除，优化链与帧长表达式必须完整，选择的 SaveVideo 输出节点必须存在且声明 filename_prefix 与 video 输入。 */
  static void validateTemplate(ObjectNode template, String saveNodeId) {
    Objects.requireNonNull(template, "template");
    for (String deleted : new String[] {"137", "138", "139", "141", "142", "143", "144"}) {
      if (template.has(deleted)) {
        throw new IllegalStateException("H3 workflow template contains sample node " + deleted);
      }
    }
    requireClass(template, "127", "UNETLoader");
    requireClass(template, "154", "PathchSageAttentionKJ");
    requireClass(template, "147", "MiniMaxH3MemoryEfficientSageAttentionPatch");
    requireClass(template, "155", "ApplyMiniMaxH3FirstBlockCache");
    requireClass(template, "156", "SpectrumApplyMiniMaxH3");
    requireClass(template, "119", "VAELoader");
    requireClass(template, "120", "VAELoader");
    requireClass(template, "125", "SamplerCustomAdvanced");
    requireClass(template, "121", "VAEDecodeAudio");
    requireClass(template, "122", "VAEDecode");
    requireClass(template, "130", "CreateVideo");
    requireClass(template, saveNodeId, "SaveVideo");
    if (!LENGTH_EXPRESSION.equals(inputs(template, "131").path("expression").asText())
        || !linkEquals(inputs(template, "154").path("model"), "127", 0)
        || !linkEquals(inputs(template, "147").path("model"), "154", 0)
        || !linkEquals(inputs(template, "155").path("model"), "147", 0)
        || !linkEquals(inputs(template, "156").path("model"), "155", 0)) {
      throw new IllegalStateException("H3 workflow template optimization/length chain is invalid");
    }
    ObjectNode saveInputs = inputs(template, saveNodeId);
    if (!saveInputs.has("filename_prefix") || !saveInputs.has("video")) {
      throw new IllegalStateException("H3 workflow template SaveVideo node is incomplete");
    }
  }

  static int frameLength(int durationSeconds) {
    int base = Math.max(5, Math.toIntExact(Math.round(durationSeconds * 24D)));
    return base + Math.floorMod(5 - Math.floorMod(base, 17), 17);
  }

  /**
   * 从冻结图构造单次 H3 API workflow：先注入已声明参数（ratio 映射、duration 与精确 seed、帧长表达式、filename_prefix），再按 manifest
   * 顺序追加 动态媒体节点。
   */
  static ObjectNode build(
      ObjectNode template,
      String saveNodeId,
      String enhancedPrompt,
      String ratio,
      int duration,
      long seed,
      UUID targetResourceId,
      H3ReferenceManifest manifest,
      Map<UUID, H3UploadedFile> uploads) {
    if (enhancedPrompt == null || enhancedPrompt.isBlank()) {
      throw new IllegalArgumentException("enhancedPrompt must not be blank");
    }
    String remoteRatio = RATIOS.get(ratio);
    if (remoteRatio == null) {
      throw new IllegalArgumentException("unsupported H3 ratio: " + ratio);
    }
    if (duration < 4 || duration > 15) {
      throw new IllegalArgumentException("duration must be between 4 and 15");
    }
    if (seed < 0L || targetResourceId == null) {
      throw new IllegalArgumentException(
          "seed must be nonnegative and targetResourceId must not be null");
    }
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(uploads, "uploads");

    ObjectNode workflow = Objects.requireNonNull(template, "template").deepCopy();
    inputs(workflow, "136").put("prompt", enhancedPrompt);
    ObjectNode resolution = inputs(workflow, "115");
    resolution.put("aspect_ratio", remoteRatio);
    resolution.put("megapixels", 0.7D);
    resolution.put("multiple", 32);
    inputs(workflow, "132").put("value", duration);
    inputs(workflow, "129").put("noise_seed", seed);
    inputs(workflow, saveNodeId).put("filename_prefix", "video/kk-studio-" + targetResourceId);
    inputs(workflow, "131").put("expression", LENGTH_EXPRESSION);

    int nodeId = DYNAMIC_NODE_START;
    for (H3ReferenceManifest.Item item : manifest.items()) {
      H3UploadedFile uploaded = uploads.get(item.reference().resourceId());
      if (uploaded == null) {
        throw new IllegalArgumentException(
            "missing ComfyUI upload for Resource " + item.reference().resourceId());
      }
      switch (item.kind()) {
        case IMAGE -> {
          String loadId = Integer.toString(nodeId++);
          workflow.set(loadId, node("LoadImage", Map.of("image", uploaded.path())));
          inputs(workflow, "136")
              .set("ref_images.ref_image_" + (item.number() - 1), link(loadId, 0));
        }
        case VIDEO -> {
          String loadId = Integer.toString(nodeId++);
          String componentsId = Integer.toString(nodeId++);
          workflow.set(loadId, node("LoadVideo", Map.of("file", uploaded.path())));
          workflow.set(componentsId, node("GetVideoComponents", Map.of("video", link(loadId, 0))));
          inputs(workflow, "136")
              .set("ref_videos.ref_video_" + (item.number() - 1), link(componentsId, 0));
          inputs(workflow, "136")
              .set(
                  "ref_video_audios.ref_video_audio_" + (item.number() - 1), link(componentsId, 1));
        }
        case AUDIO -> {
          String loadId = Integer.toString(nodeId++);
          workflow.set(loadId, node("LoadAudio", Map.of("audio", uploaded.path())));
          inputs(workflow, "136")
              .set("ref_audios.ref_audio_" + (item.number() - 1), link(loadId, 0));
        }
        case TEXT -> throw new IllegalStateException("H3 manifest must not contain TEXT");
      }
    }
    if (uploads.size() != manifest.items().size()) {
      throw new IllegalArgumentException("ComfyUI uploads must exactly match the H3 manifest");
    }
    return workflow;
  }

  private static ObjectNode node(String classType, Map<String, ?> values) {
    ObjectNode node = MAPPER.createObjectNode();
    ObjectNode inputs = node.putObject("inputs");
    for (Map.Entry<String, ?> entry : new LinkedHashMap<>(values).entrySet()) {
      inputs.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
    }
    node.put("class_type", classType);
    return node;
  }

  private static void requireClass(ObjectNode workflow, String id, String classType) {
    if (!classType.equals(workflow.path(id).path("class_type").asText())) {
      throw new IllegalStateException("H3 workflow template node " + id + " must be " + classType);
    }
  }

  private static boolean linkEquals(JsonNode value, String id, int port) {
    return value.isArray()
        && value.size() == 2
        && id.equals(value.get(0).asText())
        && port == value.get(1).asInt(-1);
  }

  private static ObjectNode inputs(ObjectNode workflow, String id) {
    if (!(workflow.path(id).path("inputs") instanceof ObjectNode inputs)) {
      throw new IllegalArgumentException("workflow node " + id + " has no inputs");
    }
    return inputs;
  }

  private static ArrayNode link(String id, int port) {
    return MAPPER.createArrayNode().add(id).add(port);
  }

  /** 预置 args schema 的已解析视图，供默认 workflow 与测试复用。 */
  static JsonObject argsSchema() {
    return CanvasJson.parseObject(ARGS_SCHEMA_JSON);
  }

  /** 默认预置图的已解析视图。 */
  static ObjectNode defaultTemplate() {
    JsonNode node = JsonValues.readTree(defaultTemplateJson());
    if (!(node instanceof ObjectNode template)) {
      throw new IllegalStateException("MiniMax-H3 workflow template must be a JSON object");
    }
    return template;
  }
}
