package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread 级 YOLO 设置请求；入队 SET_YOLO，由 Processor 在 turn 边界应用。 */
@Data
public class HarnessThreadYoloSetDTO {
  /** 是否启用 YOLO（自动批准工具）。 */
  private Boolean yoloEnabled;
}
