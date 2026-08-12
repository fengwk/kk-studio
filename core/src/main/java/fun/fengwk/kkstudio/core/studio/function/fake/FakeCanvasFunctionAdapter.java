package fun.fengwk.kkstudio.core.studio.function.fake;

import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读取 main resources 极小合法媒体、仍经过真实 materializer 的无成本 fake adapter。 */
public final class FakeCanvasFunctionAdapter implements CanvasFunctionAdapter {

  private static final String IMAGE_FIXTURE =
      "fun/fengwk/kkstudio/core/studio/function/fake/tiny.png";
  private static final String VIDEO_FIXTURE =
      "fun/fengwk/kkstudio/core/studio/function/fake/tiny.mp4";

  private static final CanvasFunctionModel IMAGE_MODEL =
      new CanvasFunctionModel(
          "fake-image",
          "Fake Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio",
                  "Ratio",
                  false,
                  "AUTO",
                  List.of("AUTO", "1:1", "3:4", "9:16", "4:3", "16:9"))));

  private static final CanvasFunctionModel VIDEO_MODEL =
      new CanvasFunctionModel(
          "fake-video",
          "Fake Video",
          CanvasResourceKind.VIDEO,
          new CanvasFunctionReferencePolicy(
              Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
              12,
              Map.of(CanvasResourceKind.VIDEO, 3, CanvasResourceKind.AUDIO, 3)),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio",
                  "Ratio",
                  true,
                  null,
                  List.of("1:1", "3:4", "16:9", "4:3", "9:16", "21:9")),
              CanvasFunctionParameterDefinition.integerParameter(
                  "duration", "Duration", false, 5, 4, 15)));

  @Override
  public List<CanvasFunctionModel> models() {
    return List.of(IMAGE_MODEL, VIDEO_MODEL);
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public String unavailableReason() {
    return null;
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    if (!run.model().key().equals(IMAGE_MODEL.key())
        && !run.model().key().equals(VIDEO_MODEL.key())) {
      throw new IllegalArgumentException("unsupported fake model: " + run.model().key());
    }
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    context.checkpoint("FAKE_RENDERING", Map.of("fixture", fixture(run)));
    ClassPathResource resource = new ClassPathResource(fixture(run));
    try (InputStream content = resource.getInputStream()) {
      UUID resourceId = context.materializeTarget(run.targetResourceId(), content);
      return List.of(resourceId);
    } catch (IOException exception) {
      throw new UncheckedIOException("failed to read fake Canvas Function fixture", exception);
    }
  }

  private static String fixture(CanvasFunctionFrozenRun run) {
    return run.model().outputKind() == CanvasResourceKind.IMAGE ? IMAGE_FIXTURE : VIDEO_FIXTURE;
  }

  private static String mediaType(CanvasFunctionFrozenRun run) {
    return run.model().outputKind() == CanvasResourceKind.IMAGE ? "image/png" : "video/mp4";
  }
}
