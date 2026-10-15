package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 标准 ComfyUI History 的归一化协议结果。
 *
 * <p>成功时只回传远端 {@code entry.outputs} 原始协议对象，不解释任何节点或字段；输出节点的解释完全由冻结工作流的 selector 在定义侧完成。
 */
public record H3ComfyHistory(Status status, ObjectNode outputs, String error) {

  public enum Status {
    /** Comfy 任务正在排队或执行中。 */
    PENDING,

    /** Comfy 任务执行成功并产出 `outputs` 协议对象。 */
    SUCCESS,

    /** Comfy 任务执行遇到错误。 */
    ERROR
  }

  public H3ComfyHistory {
    status = Objects.requireNonNull(status, "status");
    if (status == Status.SUCCESS) {
      outputs = Objects.requireNonNull(outputs, "outputs");
    }
  }

  public static H3ComfyHistory pending() {
    return new H3ComfyHistory(Status.PENDING, null, null);
  }

  public static H3ComfyHistory success(ObjectNode outputs) {
    return new H3ComfyHistory(Status.SUCCESS, outputs, null);
  }

  public static H3ComfyHistory error(String error) {
    return new H3ComfyHistory(Status.ERROR, null, error);
  }
}
