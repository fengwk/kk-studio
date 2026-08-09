package fun.fengwk.kkstudio.studio.canvas;

/** Canvas Resource 对象键的唯一生成入口。 */
public final class CanvasResourcePaths {

  private CanvasResourcePaths() {}

  public static String original(long canvasId, long resourceId) {
    validate(canvasId, resourceId);
    return prefix(canvasId, resourceId) + "/original";
  }

  public static String preview(long canvasId, long resourceId) {
    validate(canvasId, resourceId);
    return prefix(canvasId, resourceId) + "/preview.webp";
  }

  private static String prefix(long canvasId, long resourceId) {
    return "canvases/" + canvasId + "/resources/" + resourceId;
  }

  private static void validate(long canvasId, long resourceId) {
    CanvasValidation.requirePositive(canvasId, "canvasId");
    CanvasValidation.requirePositive(resourceId, "resourceId");
  }
}
