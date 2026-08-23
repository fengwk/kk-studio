package fun.fengwk.kkstudio.platform.studio.function.h3;

/** 标准 ComfyUI History 的归一化结果。 */
public record H3ComfyHistory(Status status, H3OutputDescriptor output, String error) {

  public enum Status {
    PENDING,
    SUCCESS,
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
