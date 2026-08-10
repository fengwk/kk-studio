package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 从仓库内清洁官流模板构造单次 MiniMax-H3 Ref2VA API workflow。 */
public final class H3WorkflowBuilder {

  static final String LENGTH_EXPRESSION =
      "max(5, round(a * 24)) + (5 - (max(5, round(a * 24)) % 17)) % 17";

  private static final String TEMPLATE =
      "fun/fengwk/kkstudio/core/studio/function/h3/minimax-h3-ref2va-workflow.json";
  private static final int DYNAMIC_NODE_START = 1000;
  private static final Map<String, String> RATIOS =
      Map.of(
          "21:9", "21:9 (Ultrawide)",
          "16:9", "16:9 (Widescreen)",
          "4:3", "4:3 (Standard)",
          "1:1", "1:1 (Square)",
          "3:4", "3:4 (Portrait Standard)",
          "9:16", "9:16 (Portrait Widescreen)");

  private final ObjectMapper mapper;
  private final ObjectNode template;

  public H3WorkflowBuilder(ObjectMapper objectMapper) {
    mapper = Objects.requireNonNull(objectMapper, "objectMapper");
    template = readTemplate();
    validateTemplate(template);
  }

  public ObjectNode build(
      String enhancedPrompt,
      String ratio,
      int duration,
      long seed,
      long targetResourceId,
      H3ReferenceManifest manifest,
      Map<Long, H3UploadedFile> uploads) {
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
    if (seed < 0L || targetResourceId <= 0L) {
      throw new IllegalArgumentException("seed must be nonnegative and targetResourceId positive");
    }
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(uploads, "uploads");

    ObjectNode workflow = template.deepCopy();
    inputs(workflow, "136").put("prompt", enhancedPrompt);
    ObjectNode resolution = inputs(workflow, "115");
    resolution.put("aspect_ratio", remoteRatio);
    resolution.put("megapixels", 0.7D);
    resolution.put("multiple", 32);
    inputs(workflow, "132").put("value", duration);
    inputs(workflow, "129").put("noise_seed", seed);
    inputs(workflow, "92").put("filename_prefix", "video/kk-studio-" + targetResourceId);
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

  public static int frameLength(int durationSeconds) {
    int base = Math.max(5, Math.toIntExact(Math.round(durationSeconds * 24D)));
    return base + Math.floorMod(5 - Math.floorMod(base, 17), 17);
  }

  private ObjectNode node(String classType, Map<String, ?> values) {
    ObjectNode node = mapper.createObjectNode();
    ObjectNode inputs = node.putObject("inputs");
    for (Map.Entry<String, ?> entry : new LinkedHashMap<>(values).entrySet()) {
      inputs.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
    }
    node.put("class_type", classType);
    return node;
  }

  private ObjectNode readTemplate() {
    try {
      return (ObjectNode) mapper.readTree(new ClassPathResource(TEMPLATE).getInputStream());
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("H3 workflow template is invalid JSON", error);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read H3 workflow template", error);
    }
  }

  private static void validateTemplate(ObjectNode template) {
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
    requireClass(template, "92", "SaveVideo");
    if (!LENGTH_EXPRESSION.equals(inputs(template, "131").path("expression").asText())
        || !linkEquals(inputs(template, "154").path("model"), "127", 0)
        || !linkEquals(inputs(template, "147").path("model"), "154", 0)
        || !linkEquals(inputs(template, "155").path("model"), "147", 0)
        || !linkEquals(inputs(template, "156").path("model"), "155", 0)) {
      throw new IllegalStateException("H3 workflow template optimization/length chain is invalid");
    }
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

  private ArrayNode link(String id, int port) {
    return mapper.createArrayNode().add(id).add(port);
  }
}
