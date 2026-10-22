package fun.fengwk.kkstudio.canvas;

/** typed command 或领域值不合法：参数形状、名称、引用或节点不变量无法满足。 */
public class CanvasValidationException extends IllegalArgumentException {

  public CanvasValidationException(String message) {
    super(message);
  }

  public CanvasValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
