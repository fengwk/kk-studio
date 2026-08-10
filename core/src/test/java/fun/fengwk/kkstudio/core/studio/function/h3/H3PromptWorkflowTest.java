package fun.fengwk.kkstudio.core.studio.function.h3;

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

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** prompt 与 workflow 共用同一 manifest 编号，避免附件标签和动态槽位漂移。 */
class H3PromptWorkflowTest {

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
            7L,
            8L,
            "h3",
            "request",
            mock(CanvasFunctionModel.class),
            new CanvasFunctionConfig(
                List.of(
                    new TextSegment("Use "),
                    new ReferenceSegment(2L, 1),
                    new TextSegment(" then "),
                    new ReferenceSegment(3L, 0)),
                Map.of("ratio", "16:9", "duration", 5)),
            references,
            "result",
            99L,
            "QUEUED",
            Map.of());
    AgentMessage user =
        promptBuilder.userMessage(
            run, manifest, item -> "https://signed.test/" + item.reference().resourceId());

    String text = assertInstanceOf(TextMessageContent.class, user.contents().get(0)).text();
    assertTrue(text.contains("Use <Picture 1> then <Video 1>"));
    assertTrue(text.contains("<Audio 1> | AUDIO"));
    assertTrue(text.contains("embedded audio, if present, belongs to this same video label"));
    assertInstanceOf(ImageMessageContent.class, user.contents().get(2));
    assertInstanceOf(VideoMessageContent.class, user.contents().get(4));
    assertInstanceOf(AudioMessageContent.class, user.contents().get(6));

    Map<Long, H3UploadedFile> uploads = new LinkedHashMap<>();
    uploads.put(11L, new H3UploadedFile("11.png", "kk-studio/7", "input"));
    uploads.put(12L, new H3UploadedFile("12.mp4", "kk-studio/7", "input"));
    uploads.put(13L, new H3UploadedFile("13.mp3", "kk-studio/7", "input"));
    ObjectNode workflow =
        new H3WorkflowBuilder(mapper).build("enhanced", "16:9", 5, 123L, 99L, manifest, uploads);
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
                99L,
                manifest,
                Map.of(11L, new H3UploadedFile("11.png", "kk-studio/7", "input")));
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
        "video/kk-studio-99", workflow.path("92").path("inputs").path("filename_prefix").asText());
    assertEquals(
        H3WorkflowBuilder.LENGTH_EXPRESSION,
        workflow.path("131").path("inputs").path("expression").asText());
    assertEquals(243, H3WorkflowBuilder.frameLength(10));
    assertEquals(362, H3WorkflowBuilder.frameLength(15));
    assertThrows(
        IllegalArgumentException.class,
        () -> new H3WorkflowBuilder(mapper).build("prompt", "2:1", 5, 1L, 99L, manifest, Map.of()));
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

  private static CanvasFunctionFrozenReference reference(
      long resourceId,
      long sourceNodeId,
      int sourceIndex,
      CanvasResourceKind kind,
      String mediaType) {
    return new CanvasFunctionFrozenReference(
        sourceNodeId, sourceIndex, resourceId, kind, resourceId + ".bin", mediaType, 1024L, "{}");
  }
}
