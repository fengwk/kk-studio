package fun.fengwk.kkstudio.platform.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** prompt 与 workflow 共用同一 manifest 编号，避免附件标签和动态槽位漂移。 */
class H3PromptWorkflowTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000063");

  private final ObjectMapper mapper = new ObjectMapper();

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
            mock(CanvasFunctionModel.class),
            new CanvasFunctionConfig(
                List.of(
                    new TextSegment("Use "),
                    new ReferenceSegment(new UUID(0L, 2L), 1),
                    new TextSegment(" then "),
                    new ReferenceSegment(new UUID(0L, 3L), 0)),
                Map.of("ratio", "16:9", "duration", 5)),
            references,
            "result",
            TARGET,
            "QUEUED",
            Map.of());
    AgentMessage user = promptBuilder.userMessage(run, manifest);

    String text = assertInstanceOf(TextMessageContent.class, user.contents().get(0)).text();
    assertTrue(text.contains("Use <Picture 1> then <Video 1>"));
    assertTrue(text.contains("<Audio 1> | AUDIO"));
    assertTrue(text.contains("embedded audio, if present, belongs to this same video label"));
    // durable-safe 消息：manifest 表格 + 每个引用一个 label 段落，媒体内容由入队 preflight 物化。
    assertEquals(1 + manifest.items().size(), user.contents().size());
    for (int i = 1; i < user.contents().size(); i++) {
      String label = assertInstanceOf(TextMessageContent.class, user.contents().get(i)).text();
      assertTrue(label.startsWith("\nThe next attachment is "), label);
    }

    Map<UUID, H3UploadedFile> uploads = new LinkedHashMap<>();
    uploads.put(resource(11L), new H3UploadedFile("11.png", "kk-studio/7", "input"));
    uploads.put(resource(12L), new H3UploadedFile("12.mp4", "kk-studio/7", "input"));
    uploads.put(resource(13L), new H3UploadedFile("13.mp3", "kk-studio/7", "input"));
    ObjectNode workflow =
        new H3WorkflowBuilder(mapper).build("enhanced", "16:9", 5, 123L, TARGET, manifest, uploads);
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
        new H3WorkflowBuilder(mapper)
            .build(
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
        () ->
            new H3WorkflowBuilder(mapper)
                .build("prompt", "2:1", 5, 1L, TARGET, manifest, Map.of()));
    CanvasFunctionFrozenReference textReference =
        reference(12L, 2L, 2, CanvasResourceKind.TEXT, "text/plain");
    assertThrows(
        IllegalArgumentException.class, () -> new H3ReferenceManifest.Item(textReference, 0));
    assertThrows(
        IllegalStateException.class, () -> new H3ReferenceManifest.Item(textReference, 1).label());
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
