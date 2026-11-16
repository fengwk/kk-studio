package fun.fengwk.kkstudio.canvas.infra.function.fake;

import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读取 main resources 极小合法媒体、仍经过真实 materializer 的无成本 fake adapter；没有外部副作用，因此不写 checkpoint。 */
public final class FakeCanvasFunctionAdapter implements CanvasFunctionAdapter {

  private static final String IMAGE_FIXTURE =
      "fun/fengwk/kkstudio/canvas/infra/function/fake/tiny.png";

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

  private static final JsonObject REPORT_ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "Fake report parameters",
            "additionalProperties": false,
            "properties": {
              "prompt": {
                "type": "string",
                "description": "Prompt text"
              }
            }
          }
          """);

  private static final CanvasFunctionDefinition IMAGE_FUNCTION =
      CanvasFunctionDefinition.of(
          "fake-image",
          "Fake Image",
          IMAGE_ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()));

  private static final CanvasFunctionDefinition REPORT_FUNCTION =
      new CanvasFunctionDefinition(
          "fake-report",
          "Fake Report",
          REPORT_ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "report.txt"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "chart.png")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()));

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return List.of(IMAGE_FUNCTION, REPORT_FUNCTION);
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
        && !run.definition().name().equals(REPORT_FUNCTION.name())) {
      throw new IllegalArgumentException("unsupported fake function: " + run.definition().name());
    }
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    // 免费、同步、可重复的本地 fixture 没有需要恢复的外部任务身份。
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    if (run.definition().name().equals(REPORT_FUNCTION.name())) {
      CanvasFunctionFrozenOutput textOutput = run.output(0);
      CanvasFunctionFrozenOutput imageOutput = run.output(1);
      UUID textId = context.materializeTextOutput(textOutput, "Fake report for " + run.nodeName());
      ClassPathResource resource = new ClassPathResource(IMAGE_FIXTURE);
      try (InputStream content = resource.getInputStream()) {
        UUID imageId = context.materializeOutput(imageOutput, content);
        return List.of(textId, imageId);
      } catch (IOException exception) {
        throw new UncheckedIOException("failed to read fake Canvas Function fixture", exception);
      }
    }
    ClassPathResource resource = new ClassPathResource(IMAGE_FIXTURE);
    try (InputStream content = resource.getInputStream()) {
      UUID resourceId = context.materializeOutput(run.output(0), content);
      return List.of(resourceId);
    } catch (IOException exception) {
      throw new UncheckedIOException("failed to read fake Canvas Function fixture", exception);
    }
  }
}
