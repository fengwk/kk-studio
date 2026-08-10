package fun.fengwk.kkstudio.core.studio.function.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** fake descriptors 精确声明 v1 能力，执行至少 checkpoint 一次并只写 frozen target。 */
class FakeCanvasFunctionAdapterTest {

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
            1L,
            2L,
            "output",
            "request",
            image,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of("ratio", "AUTO")),
            List.of(),
            "output.png",
            3L,
            "QUEUED",
            Map.of());
    assertEquals(List.of(3L), adapter.execute(context, run));
    assertEquals(List.of("FAKE_RENDERING"), context.stages);
    assertEquals(3L, context.targetId);
    assertEquals("image/png", context.mediaType);
    assertTrue(context.size > 0L);

    RecordingContext videoContext = new RecordingContext();
    CanvasFunctionFrozenRun videoRun =
        new CanvasFunctionFrozenRun(
            1L,
            2L,
            "output",
            "request",
            video,
            new CanvasFunctionConfig(
                List.of(new TextSegment("prompt")), Map.of("ratio", "16:9", "duration", 5)),
            List.of(),
            "output.mp4",
            4L,
            "QUEUED",
            Map.of());
    assertEquals(List.of(4L), adapter.execute(videoContext, videoRun));
    assertEquals("video/mp4", videoContext.mediaType);
    assertTrue(videoContext.size > 0L);

    CanvasFunctionModel unsupported =
        new CanvasFunctionModel(
            "unsupported",
            "Unsupported",
            CanvasResourceKind.IMAGE,
            image.referencePolicy(),
            image.parameters());
    CanvasFunctionFrozenRun unsupportedRun =
        new CanvasFunctionFrozenRun(
            1L,
            2L,
            "output",
            "request",
            unsupported,
            run.config(),
            List.of(),
            "output.png",
            5L,
            "QUEUED",
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> adapter.preflight(unsupportedRun));
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private long targetId;
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
    public long materializeTarget(
        long targetResourceId, String mediaType, long size, InputStream content) {
      this.targetId = targetResourceId;
      this.mediaType = mediaType;
      this.size = size;
      return targetResourceId;
    }
  }
}
