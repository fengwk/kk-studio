package fun.fengwk.kkstudio.studio.canvas;

/** ResourceNode 上可选的资源生产配置。 */
public record CanvasFunction(String modelKey, String configJson) {

  public CanvasFunction {
    CanvasValidation.requireNonBlank(modelKey, "modelKey");
    CanvasValidation.requireNonBlank(configJson, "configJson");
  }
}
