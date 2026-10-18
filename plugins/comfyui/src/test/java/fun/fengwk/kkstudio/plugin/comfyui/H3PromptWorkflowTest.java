package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** prompt 与 workflow 共用同一 manifest 编号，避免附件标签和动态槽位漂移。 */
class H3PromptWorkflowTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000063");

  /** 预置图的冻结构造入口：默认 SaveVideo 节点即预置 selector 声明的节点。 */
  private static ObjectNode buildWorkflow(
      String prompt,
      String ratio,
      int duration,
      long seed,
      UUID target,
      H3ReferenceManifest manifest,
      Map<UUID, H3UploadedFile> uploads) {
    return H3WorkflowBuilder.build(
        H3WorkflowBuilder.defaultTemplate(),
        "92",
        prompt,
        ratio,
        duration,
        seed,
        target,
        manifest,
        uploads);
  }

  @Test
  void sharesStablePerKindNumberingAcrossTextMediaAndWorkflowSlots() {
    List<CanvasFunctionFrozenReference> references =
        List.of(
            reference(11L, 2L, 1, CanvasResourceKind.IMAGE, "image/png"),
            reference(12L, 3L, 0, CanvasResourceKind.VIDEO, "video/mp4"),
            reference(13L, 4L, 2, CanvasResourceKind.AUDIO, "audio/mpeg"));
    H3ReferenceManifest manifest = H3ReferenceManifest.from(references);

    assertEquals(
        List.of("<Picture 1>", "<Video 1>", "<Audio 1>"),
        manifest.items().stream().map(H3ReferenceManifest.Item::label).toList());

    H3PromptRequestBuilder promptBuilder = new H3PromptRequestBuilder();
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "h3",
            REQUEST,
            new CanvasFunctionBinding(videoDefinition()),
            CanvasJson.parseObject(
                "{\"prompt\":\"Use <Picture 1> then <Video 1>\",\"ratio\":\"16:9\",\"duration\":5}"),
            references,
            List.of(
                new CanvasFunctionFrozenOutput(TARGET, 0, CanvasResourceKind.VIDEO, "result.mp4")),
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    String table = promptBuilder.manifestTable(run, manifest).text();

    assertTrue(table.contains("Use <Picture 1> then <Video 1>"));
    assertTrue(table.contains("<Audio 1> | AUDIO"));
    assertTrue(table.contains("embedded audio, if present, belongs to this same video label"));
    // 产物只是 durable-safe 文本片段：manifest 表格 + 每个引用一个 label；媒体由调用方在暂存后按同一顺序插入。
    for (H3ReferenceManifest.Item item : manifest.items()) {
      PluginPromptContent.Text label = promptBuilder.referenceLabel(item);
      assertTrue(label.text().startsWith("\nThe next attachment is " + item.label()), label.text());
    }

    Map<UUID, H3UploadedFile> uploads = new LinkedHashMap<>();
    uploads.put(resource(11L), new H3UploadedFile("11.png", "kk-studio/7", "input"));
    uploads.put(resource(12L), new H3UploadedFile("12.mp4", "kk-studio/7", "input"));
    uploads.put(resource(13L), new H3UploadedFile("13.mp3", "kk-studio/7", "input"));
    ObjectNode workflow = buildWorkflow("enhanced", "16:9", 5, 123L, TARGET, manifest, uploads);
    JsonNode inputs = workflow.path("136").path("inputs");
    assertEquals("1000", inputs.path("ref_images.ref_image_0").get(0).asText());
    assertEquals("1002", inputs.path("ref_videos.ref_video_0").get(0).asText());
    assertEquals(0, inputs.path("ref_videos.ref_video_0").get(1).asInt());
    assertEquals("1002", inputs.path("ref_video_audios.ref_video_audio_0").get(0).asText());
    assertEquals(1, inputs.path("ref_video_audios.ref_video_audio_0").get(1).asInt());
    assertEquals("1003", inputs.path("ref_audios.ref_audio_0").get(0).asText());
  }

  @Test
  void systemPromptHasExactlySixFieldsAndWorkflowKeepsGoldenInvariants() {
    String system = new H3PromptRequestBuilder().systemPrompt();
    for (String field :
        List.of(
            "subject_definitions",
            "summary",
            "retention_analysis",
            "detailed_description",
            "overall_soundscape",
            "non_diegetic_music")) {
      assertEquals(1, occurrences(system, field + ":"));
    }

    H3ReferenceManifest manifest =
        H3ReferenceManifest.from(
            List.of(reference(11L, 2L, 1, CanvasResourceKind.IMAGE, "image/png")));
    ObjectNode workflow =
        buildWorkflow(
            "prompt",
            "9:16",
            15,
            0L,
            TARGET,
            manifest,
            Map.of(resource(11L), new H3UploadedFile("11.png", "kk-studio/7", "input")));
    for (String deleted : List.of("137", "138", "139", "141", "142", "143", "144")) {
      assertFalse(workflow.has(deleted));
    }
    assertEquals(
        "9:16 (Portrait Widescreen)",
        workflow.path("115").path("inputs").path("aspect_ratio").asText());
    assertEquals(0.7D, workflow.path("115").path("inputs").path("megapixels").asDouble());
    assertEquals(32, workflow.path("115").path("inputs").path("multiple").asInt());
    assertEquals(15, workflow.path("132").path("inputs").path("value").asInt());
    assertEquals(0L, workflow.path("129").path("inputs").path("noise_seed").asLong());
    assertEquals(
        "video/kk-studio-" + TARGET,
        workflow.path("92").path("inputs").path("filename_prefix").asText());
    assertEquals(
        H3WorkflowBuilder.LENGTH_EXPRESSION,
        workflow.path("131").path("inputs").path("expression").asText());
    assertEquals(243, H3WorkflowBuilder.frameLength(10));
    assertEquals(362, H3WorkflowBuilder.frameLength(15));
    assertThrows(
        IllegalArgumentException.class,
        () -> buildWorkflow("prompt", "2:1", 5, 1L, TARGET, manifest, Map.of()));
    CanvasFunctionFrozenReference textReference =
        reference(12L, 2L, 2, CanvasResourceKind.TEXT, "text/plain");
    assertThrows(
        IllegalArgumentException.class, () -> new H3ReferenceManifest.Item(textReference, 0));
    assertThrows(
        IllegalStateException.class, () -> new H3ReferenceManifest.Item(textReference, 1).label());
  }

  /** H3 模板是自由 JSON 图：number 必须按 BigDecimal 搬运，高精度字面量不得在 double 中失真或被 strip。 */
  @Test
  void preservesTemplateDecimalLiteralsAsBigDecimal() {
    H3ReferenceManifest manifest =
        H3ReferenceManifest.from(
            List.of(reference(11L, 2L, 1, CanvasResourceKind.IMAGE, "image/png")));
    ObjectNode workflow =
        buildWorkflow(
            "prompt",
            "16:9",
            5,
            0L,
            TARGET,
            manifest,
            Map.of(resource(11L), new H3UploadedFile("11.png", "kk-studio/7", "input")));

    JsonNode firstBlockCache = workflow.path("155").path("inputs");
    assertTrue(firstBlockCache.path("end_percent").isBigDecimal());
    assertEquals(new BigDecimal("0.95"), firstBlockCache.path("end_percent").decimalValue());
    JsonNode spectrum = workflow.path("156").path("inputs");
    assertTrue(spectrum.path("flex_window").isBigDecimal());
    assertEquals(new BigDecimal("0.75"), spectrum.path("flex_window").decimalValue());
    assertEquals(new BigDecimal("0.1"), spectrum.path("ridge_lambda").decimalValue());
  }

  /** 正常媒体引用仍从 1000 起的保留动态区分配节点，且不覆盖任何静态模板节点。 */
  @Test
  void allocatesDynamicMediaNodesInTheReservedRegion() {
    List<CanvasFunctionFrozenReference> references =
        List.of(
            reference(11L, 2L, 1, CanvasResourceKind.IMAGE, "image/png"),
            reference(12L, 3L, 0, CanvasResourceKind.VIDEO, "video/mp4"),
            reference(13L, 4L, 2, CanvasResourceKind.AUDIO, "audio/mpeg"));
    H3ReferenceManifest manifest = H3ReferenceManifest.from(references);
    int staticNodes = H3WorkflowBuilder.defaultTemplate().size();

    ObjectNode workflow =
        buildWorkflow(
            "prompt",
            "16:9",
            5,
            1L,
            TARGET,
            manifest,
            Map.of(
                resource(11L), new H3UploadedFile("11.png", "kk-studio/7", "input"),
                resource(12L), new H3UploadedFile("12.mp4", "kk-studio/7", "input"),
                resource(13L), new H3UploadedFile("13.mp3", "kk-studio/7", "input")));

    assertEquals(staticNodes + 4, workflow.size());
    assertEquals("LoadImage", workflow.path("1000").path("class_type").asText());
    assertEquals("LoadVideo", workflow.path("1001").path("class_type").asText());
    assertEquals("GetVideoComponents", workflow.path("1002").path("class_type").asText());
    assertEquals("LoadAudio", workflow.path("1003").path("class_type").asText());
    assertEquals(
        "1000",
        workflow.path("136").path("inputs").path("ref_images.ref_image_0").path(0).asText());
    // 保留区判定只覆盖规范十进制 id：边界之下与混合 id 都不属于动态区。
    assertTrue(H3WorkflowBuilder.isReservedDynamicNodeId("1000"));
    assertTrue(H3WorkflowBuilder.isReservedDynamicNodeId("01000"));
    assertFalse(H3WorkflowBuilder.isReservedDynamicNodeId("999"));
    assertFalse(H3WorkflowBuilder.isReservedDynamicNodeId("a1000"));
    assertFalse(H3WorkflowBuilder.isReservedDynamicNodeId(""));
  }

  /** prompt/workflow 测试只关心冻结计划形状：单 VIDEO 槽位。 */
  private static CanvasFunctionDefinition videoDefinition() {
    CanvasFunctionDefinition definition = mock(CanvasFunctionDefinition.class);
    when(definition.outputs())
        .thenReturn(List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.VIDEO)));
    return definition;
  }

  private static int occurrences(String text, String value) {
    int count = 0;
    int position = 0;
    while ((position = text.indexOf(value, position)) >= 0) {
      count++;
      position += value.length();
    }
    return count;
  }

  private static UUID resource(long value) {
    return new UUID(0L, value);
  }

  private static CanvasFunctionFrozenReference reference(
      long resourceId,
      long sourceNodeId,
      int sourceIndex,
      CanvasResourceKind kind,
      String mediaType) {
    UUID resource = resource(resourceId);
    return new CanvasFunctionFrozenReference(
        new UUID(0L, sourceNodeId),
        sourceIndex,
        resource,
        resource,
        kind,
        resourceId + ".bin",
        mediaType,
        1024L,
        512L,
        512L,
        null);
  }
}
