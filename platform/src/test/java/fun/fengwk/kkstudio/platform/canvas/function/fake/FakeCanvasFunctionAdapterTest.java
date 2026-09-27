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
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.io.InputStream;
import java.util.ArrayList;
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

  @Test
  void exposesExactFunctionsAndExecutesMainResourceFixture() {
    FakeCanvasFunctionAdapter adapter = new FakeCanvasFunctionAdapter();
    assertEquals(
        2, new FakeCanvasFunctionConfiguration().fakeCanvasFunctionAdapter().functions().size());
    assertTrue(adapter.enabled());
    assertNull(adapter.unavailableReason());
    CanvasFunctionDefinition image = adapter.functions().get(0);
    CanvasFunctionDefinition video = adapter.functions().get(1);
    assertEquals("fake-image", image.name());
    assertEquals(CanvasResourceKind.IMAGE, image.outputKind());
    assertEquals("fake-video", video.name());
    assertEquals(CanvasResourceKind.VIDEO, video.outputKind());
    assertEquals(12, video.referencePolicy().maxReferences());
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.VIDEO));
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.AUDIO));

    RecordingContext context = new RecordingContext();
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            image,
            CanvasJson.parseObject("{\"prompt\":\"test\",\"ratio\":\"AUTO\"}"),
            List.of(),
            "output.png",
            IMAGE_TARGET,
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.submit(context, run);
    assertEquals(List.of("FAKE_SUBMITTED"), context.stages);

    assertEquals(List.of(IMAGE_TARGET), adapter.execute(context, run));
    assertEquals(List.of("FAKE_SUBMITTED", "FAKE_RENDERING"), context.stages);
    assertEquals(IMAGE_TARGET, context.targetId);

    RecordingContext videoContext = new RecordingContext();
    CanvasFunctionFrozenRun videoRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            video,
            CanvasJson.parseObject("{\"prompt\":\"test\",\"ratio\":\"16:9\",\"duration\":5}"),
            List.of(),
            "output.mp4",
            VIDEO_TARGET,
            CanvasFunctionSubmitState.SUBMITTED,
            "QUEUED",
            Map.of());
    adapter.submit(videoContext, videoRun);
    assertEquals(List.of(VIDEO_TARGET), adapter.execute(videoContext, videoRun));

    CanvasFunctionDefinition unsupported =
        new CanvasFunctionDefinition(
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
            "output.png",
            IMAGE_TARGET,
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> adapter.preflight(unsupportedRun));
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private UUID targetId;

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
    public UUID materializeTarget(UUID targetResourceId, InputStream content) {
      this.targetId = targetResourceId;
      return targetResourceId;
    }
  }
}
