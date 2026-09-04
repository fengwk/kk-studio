package fun.fengwk.kkstudio.platform.canvas.function.h3;

/** 标准 ComfyUI History 的归一化结果。 */
public record H3ComfyHistory(Status status, H3OutputDescriptor output, String error) {

  public enum Status {
    /** Comfy 任务正在排队或执行中。 */
    PENDING,

    /** Comfy 任务执行成功并产出结果。 */
    SUCCESS,

    /** Comfy 任务执行遇到错误。 */
    ERROR
  }

  public static H3ComfyHistory pending() {
    return new H3ComfyHistory(Status.PENDING, null, null);
  }

  public static H3ComfyHistory success(H3OutputDescriptor output) {
    return new H3ComfyHistory(Status.SUCCESS, output, null);
  }

  public static H3ComfyHistory error(String error) {
    return new H3ComfyHistory(Status.ERROR, null, error);
  }
}
