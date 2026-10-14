package fun.fengwk.kkstudio.platform.canvas.function.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** fake descriptors 精确声明 v1 能力，执行 submit/execute 并只写 frozen target。 */
class FakeCanvasFunctionAdapterTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID IMAGE_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID VIDEO_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID REPORT_TEXT = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID REPORT_IMAGE = UUID.fromString("00000000-0000-0000-0000-000000000007");

  @Test
  void exposesExactFunctionsAndExecutesMainResourceFixture() {
    FakeCanvasFunctionAdapter adapter = new FakeCanvasFunctionAdapter();
    assertEquals(
        3, new FakeCanvasFunctionConfiguration().fakeCanvasFunctionAdapter().functions().size());
    assertTrue(adapter.enabled());
    assertNull(adapter.unavailableReason());
    CanvasFunctionDefinition image = adapter.functions().get(0);
    CanvasFunctionDefinition video = adapter.functions().get(1);
    CanvasFunctionDefinition report = adapter.functions().get(2);
    assertEquals("fake-image", image.name());
    assertEquals(List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)), image.outputs());
    assertEquals("fake-video", video.name());
    assertEquals(List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.VIDEO)), video.outputs());
    assertEquals(12, video.referencePolicy().maxReferences());
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.VIDEO));
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.AUDIO));

    assertEquals("fake-report", report.name());
    assertEquals(
        List.of(
            CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "report.txt"),
            CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "chart.png")),
        report.outputs());

    RecordingContext context = new RecordingContext();
    CanvasFunctionFrozenOutput imageOutput =
        new CanvasFunctionFrozenOutput(IMAGE_TARGET, 0, CanvasResourceKind.IMAGE, "output.png");
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            image,
            CanvasJson.parseObject("{\"prompt\":\"test\",\"ratio\":\"AUTO\"}"),
            List.of(),
            List.of(imageOutput),
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.submit(context, run);
    assertEquals(List.of("FAKE_SUBMITTED"), context.stages);

    assertEquals(List.of(IMAGE_TARGET), adapter.execute(context, run));
    assertEquals(List.of("FAKE_SUBMITTED", "FAKE_RENDERING"), context.stages);
    assertEquals(IMAGE_TARGET, context.materializedBlobId);

    RecordingContext videoContext = new RecordingContext();
    CanvasFunctionFrozenOutput videoOutput =
        new CanvasFunctionFrozenOutput(VIDEO_TARGET, 0, CanvasResourceKind.VIDEO, "output.mp4");
    CanvasFunctionFrozenRun videoRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            video,
            CanvasJson.parseObject("{\"prompt\":\"test\",\"ratio\":\"16:9\",\"duration\":5}"),
            List.of(),
            List.of(videoOutput),
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            Map.of());
    adapter.submit(videoContext, videoRun);
    assertEquals(List.of(VIDEO_TARGET), adapter.execute(videoContext, videoRun));

    RecordingContext reportContext = new RecordingContext();
    CanvasFunctionFrozenOutput reportTextOutput =
        new CanvasFunctionFrozenOutput(REPORT_TEXT, 0, CanvasResourceKind.TEXT, "report.txt");
    CanvasFunctionFrozenOutput reportChartOutput =
        new CanvasFunctionFrozenOutput(REPORT_IMAGE, 1, CanvasResourceKind.IMAGE, "chart.png");
    CanvasFunctionFrozenRun reportRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            report,
            CanvasJson.parseObject("{\"prompt\":\"report test\"}"),
            List.of(),
            List.of(reportTextOutput, reportChartOutput),
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.submit(reportContext, reportRun);
    assertEquals(List.of("FAKE_SUBMITTED"), reportContext.stages);

    List<UUID> reportResult = adapter.execute(reportContext, reportRun);
    assertEquals(List.of(REPORT_TEXT, REPORT_IMAGE), reportResult);
    assertEquals(List.of("FAKE_SUBMITTED", "FAKE_REPORTING"), reportContext.stages);
    assertEquals("Fake report for output", reportContext.materializedText.get(REPORT_TEXT));
    assertEquals(REPORT_IMAGE, reportContext.materializedBlobId);

    assertThrows(
        IllegalArgumentException.class,
        () -> reportContext.materializeOutput(reportTextOutput, InputStream.nullInputStream()));
    assertThrows(
        IllegalArgumentException.class,
        () -> reportContext.materializeTextOutput(reportChartOutput, "not text"));

    CanvasFunctionDefinition unsupported =
        CanvasFunctionDefinition.of(
            "unsupported",
            "Unsupported",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    CanvasFunctionFrozenRun unsupportedRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            unsupported,
            CanvasJson.parseObject("{}"),
            List.of(),
            List.of(imageOutput),
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> adapter.preflight(unsupportedRun));
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private final Map<UUID, String> materializedText = new LinkedHashMap<>();
    private UUID materializedBlobId;

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      if (output.inlineText()) {
        throw new IllegalArgumentException(
            "TEXT output must be materialized through materializeTextOutput");
      }
      this.materializedBlobId = output.resourceId();
      return output.resourceId();
    }

    @Override
    public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
      if (!output.inlineText()) {
        throw new IllegalArgumentException(
            "only a TEXT output slot can be materialized as inline text");
      }
      this.materializedText.put(output.resourceId(), text);
      return output.resourceId();
    }
  }
}
