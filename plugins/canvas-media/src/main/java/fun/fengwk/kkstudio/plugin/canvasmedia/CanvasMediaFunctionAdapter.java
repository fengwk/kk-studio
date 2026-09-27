package fun.fengwk.kkstudio.plugin.canvasmedia;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import javax.imageio.ImageIO;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 基础媒体处理 Canvas Function 适配器，提供本地无模型依赖的图像裁剪（{@code image.crop}）能力。 */
public class CanvasMediaFunctionAdapter implements CanvasFunctionAdapter {

  public static final String FUNCTION_NAME = "image.crop";

  private static final JsonObject ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "Image crop parameters",
            "additionalProperties": false,
            "properties": {
              "source": {
                "type": "resourceReference",
                "description": "Source image reference"
              },
              "x": {
                "type": "integer",
                "description": "X coordinate of crop area top-left corner",
                "minimum": 0,
                "default": 0
              },
              "y": {
                "type": "integer",
                "description": "Y coordinate of crop area top-left corner",
                "minimum": 0,
                "default": 0
              },
              "width": {
                "type": "integer",
                "description": "Width of cropped rectangle",
                "minimum": 1
              },
              "height": {
                "type": "integer",
                "description": "Height of cropped rectangle",
                "minimum": 1
              }
            },
            "required": [
              "source",
              "width",
              "height"
            ]
          }
          """);

  private static final CanvasFunctionReferencePolicy REFERENCE_POLICY =
      new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of());

  private static final CanvasFunctionDefinition IMAGE_CROP_DEFINITION =
      new CanvasFunctionDefinition(
          FUNCTION_NAME,
          "Crop a rectangular region from an input image",
          ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          REFERENCE_POLICY);

  private static final List<CanvasFunctionDefinition> DEFINITIONS = List.of(IMAGE_CROP_DEFINITION);

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return DEFINITIONS;
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
    Objects.requireNonNull(run, "run");
    List<CanvasFunctionFrozenReference> manifest = run.manifest();
    if (manifest == null || manifest.size() != 1) {
      throw new IllegalArgumentException("image.crop requires exactly one input image reference");
    }
    CanvasFunctionFrozenReference reference = manifest.get(0);
    if (reference.kind() != CanvasResourceKind.IMAGE) {
      throw new IllegalArgumentException("image.crop input reference must be an IMAGE");
    }
    if (reference.width() == null || reference.height() == null) {
      throw new IllegalArgumentException("source image dimensions must be present in frozen facts");
    }
    CropRectangle rect = CropRectangle.from(run.args());
    if ((long) rect.x() + rect.width() > reference.width()
        || (long) rect.y() + rect.height() > reference.height()) {
      throw new IllegalArgumentException(
          String.format(
              "crop rectangle [x=%d, y=%d, width=%d, height=%d] exceeds source image dimensions [width=%d, height=%d]",
              rect.x(),
              rect.y(),
              rect.width(),
              rect.height(),
              reference.width(),
              reference.height()));
    }
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    // image.crop is an in-process, model-free computation executed directly in execute() via
    // standard JDK ImageIO.
    // There is no external service, remote queue, or asynchronous worker to submit to.
    // The Runtime persists the SUBMITTING -> SUBMITTED lifecycle transitions around this
    // invocation.
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(run, "run");
    preflight(run);

    CropRectangle rect = CropRectangle.from(run.args());
    context.checkpoint(
        "CROPPING",
        Map.of(
            "x", rect.x(),
            "y", rect.y(),
            "width", rect.width(),
            "height", rect.height()));

    CanvasFunctionFrozenReference reference = run.manifest().get(0);
    BufferedImage sourceImage;
    try (CanvasFunctionResourceStream stream = context.openOriginal(reference)) {
      sourceImage = ImageIO.read(stream.content());
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to read source image stream", exception);
    }
    if (sourceImage == null) {
      throw new IllegalArgumentException(
          "Failed to decode source image: unsupported format or corrupt data");
    }

    if (rect.x() + rect.width() > sourceImage.getWidth()
        || rect.y() + rect.height() > sourceImage.getHeight()) {
      throw new IllegalArgumentException(
          String.format(
              "crop rectangle [x=%d, y=%d, width=%d, height=%d] exceeds decoded image dimensions [width=%d, height=%d]",
              rect.x(),
              rect.y(),
              rect.width(),
              rect.height(),
              sourceImage.getWidth(),
              sourceImage.getHeight()));
    }

    int imageType =
        sourceImage.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB
            : BufferedImage.TYPE_INT_RGB;
    BufferedImage croppedImage = new BufferedImage(rect.width(), rect.height(), imageType);
    Graphics2D graphics = croppedImage.createGraphics();
    try {
      graphics.drawImage(
          sourceImage,
          0,
          0,
          rect.width(),
          rect.height(),
          rect.x(),
          rect.y(),
          rect.x() + rect.width(),
          rect.y() + rect.height(),
          null);
    } finally {
      graphics.dispose();
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try {
      boolean written = ImageIO.write(croppedImage, "PNG", outputStream);
      if (!written) {
        throw new IllegalStateException("No PNG ImageWriter found");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to encode cropped image as PNG", exception);
    }

    if (!context.isRunning()) {
      throw new IllegalStateException("Execution is no longer running");
    }

    UUID materializedId =
        context.materializeTarget(
            run.targetResourceId(), new ByteArrayInputStream(outputStream.toByteArray()));
    return List.of(materializedId);
  }

  @Override
  public void cancel(CanvasFunctionFrozenRun run) {
    // image.crop executes in-process and has no external task to cancel.
  }

  record CropRectangle(int x, int y, int width, int height) {

    static CropRectangle from(JsonObject args) {
      Objects.requireNonNull(args, "args");
      int x = extractInt(args, "x", 0, false);
      int y = extractInt(args, "y", 0, false);
      int width = extractInt(args, "width", null, true);
      int height = extractInt(args, "height", null, true);
      return new CropRectangle(x, y, width, height);
    }

    private static int extractInt(
        JsonObject args, String key, Integer defaultValue, boolean positive) {
      CanvasJson json = args.values().get(key);
      if (json == null) {
        if (defaultValue != null) {
          return defaultValue;
        }
        throw new IllegalArgumentException("missing required argument: " + key);
      }
      if (!(json instanceof JsonNumber number)) {
        throw new IllegalArgumentException("argument " + key + " must be an integer");
      }
      try {
        int value = number.value().intValueExact();
        if (positive && value < 1) {
          throw new IllegalArgumentException("argument " + key + " must be >= 1");
        }
        if (!positive && value < 0) {
          throw new IllegalArgumentException("argument " + key + " must be >= 0");
        }
        return value;
      } catch (ArithmeticException exception) {
        throw new IllegalArgumentException("argument " + key + " must be an integer");
      }
    }
  }
}
