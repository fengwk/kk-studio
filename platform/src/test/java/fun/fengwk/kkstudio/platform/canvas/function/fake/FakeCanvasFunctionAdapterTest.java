package fun.fengwk.kkstudio.platform.canvas.function.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** fake descriptors 精确声明 v1 能力，执行至少 checkpoint 一次并只写 frozen target。 */
class FakeCanvasFunctionAdapterTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID IMAGE_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID VIDEO_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000005");

  @Test
  void exposesExactModelsAndExecutesMainResourceFixture() {
    FakeCanvasFunctionAdapter adapter = new FakeCanvasFunctionAdapter();
    assertEquals(
        2, new FakeCanvasFunctionConfiguration().fakeCanvasFunctionAdapter().models().size());
    CanvasFunctionModel image = adapter.models().get(0);
    CanvasFunctionModel video = adapter.models().get(1);
    assertEquals("fake-image", image.key());
    assertEquals(
        List.of("AUTO", "1:1", "3:4", "9:16", "4:3", "16:9"), image.parameters().get(0).options());
    assertEquals("fake-video", video.key());
    assertEquals(12, video.referencePolicy().maxReferences());
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.VIDEO));
    assertEquals(3, video.referencePolicy().maxFor(CanvasResourceKind.AUDIO));
    assertEquals(5, video.parameters().get(1).defaultValue());

    RecordingContext context = new RecordingContext();
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            image,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of("ratio", "AUTO")),
            List.of(),
            "output.png",
            IMAGE_TARGET,
            "QUEUED",
            Map.of());
    assertEquals(List.of(IMAGE_TARGET), adapter.execute(context, run));
    assertEquals(List.of("FAKE_RENDERING"), context.stages);
    assertEquals(IMAGE_TARGET, context.targetId);

    RecordingContext videoContext = new RecordingContext();
    CanvasFunctionFrozenRun videoRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            video,
            new CanvasFunctionConfig(
                List.of(new TextSegment("prompt")), Map.of("ratio", "16:9", "duration", 5)),
            List.of(),
            "output.mp4",
            VIDEO_TARGET,
            "QUEUED",
            Map.of());
    assertEquals(List.of(VIDEO_TARGET), adapter.execute(videoContext, videoRun));

    CanvasFunctionModel unsupported =
        new CanvasFunctionModel(
            "unsupported",
            "Unsupported",
            CanvasResourceKind.IMAGE,
            image.referencePolicy(),
            image.parameters());
    CanvasFunctionFrozenRun unsupportedRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            unsupported,
            run.config(),
            List.of(),
            "output.png",
            IMAGE_TARGET,
            "QUEUED",
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> adapter.preflight(unsupportedRun));
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private UUID targetId;
    private String mediaType;
    private long size;

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
