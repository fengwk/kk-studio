package fun.fengwk.kkstudio.platform.canvas.function.fake;

import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

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
      "fun/fengwk/kkstudio/platform/canvas/function/fake/tiny.png";
  private static final String VIDEO_FIXTURE =
      "fun/fengwk/kkstudio/platform/canvas/function/fake/tiny.mp4";

  private static final JsonObject IMAGE_ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "Fake image parameters",
            "additionalProperties": false,
            "properties": {
              "prompt": {
                "type": "string",
                "description": "Prompt text"
              },
              "ratio": {
                "type": "string",
                "description": "Ratio",
                "enum": ["AUTO", "1:1", "3:4", "9:16", "4:3", "16:9"],
                "default": "AUTO"
              },
              "references": {
                "type": "array",
                "description": "References",
                "items": {
                  "type": "resourceReference",
                  "description": "Resource reference"
                },
                "minItems": 0,
                "maxItems": 12
              }
            }
          }
          """);

  private static final JsonObject VIDEO_ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "Fake video parameters",
            "additionalProperties": false,
            "properties": {
              "prompt": {
                "type": "string",
                "description": "Prompt text"
              },
              "ratio": {
                "type": "string",
                "description": "Ratio",
                "enum": ["1:1", "3:4", "16:9", "4:3", "9:16", "21:9"],
                "default": "16:9"
              },
              "duration": {
                "type": "integer",
                "description": "Duration",
                "minimum": 4,
                "maximum": 15,
                "default": 5
              },
              "references": {
                "type": "array",
                "description": "References",
                "items": {
                  "type": "resourceReference",
                  "description": "Resource reference"
                },
                "minItems": 0,
                "maxItems": 12
              }
            }
          }
          """);

  private static final CanvasFunctionDefinition IMAGE_FUNCTION =
      new CanvasFunctionDefinition(
          "fake-image",
          "Fake Image",
          IMAGE_ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()));

  private static final CanvasFunctionDefinition VIDEO_FUNCTION =
      new CanvasFunctionDefinition(
          "fake-video",
          "Fake Video",
          VIDEO_ARGS_SCHEMA,
          CanvasResourceKind.VIDEO,
          new CanvasFunctionReferencePolicy(
              Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
              12,
              Map.of(CanvasResourceKind.VIDEO, 3, CanvasResourceKind.AUDIO, 3)));

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return List.of(IMAGE_FUNCTION, VIDEO_FUNCTION);
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
    if (!run.definition().name().equals(IMAGE_FUNCTION.name())
        && !run.definition().name().equals(VIDEO_FUNCTION.name())) {
      throw new IllegalArgumentException("unsupported fake function: " + run.definition().name());
    }
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    context.checkpoint("FAKE_SUBMITTED", Map.of());
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
    return run.definition().outputKind() == CanvasResourceKind.IMAGE
        ? IMAGE_FIXTURE
        : VIDEO_FIXTURE;
  }
}
