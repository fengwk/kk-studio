package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread 级 YOLO 设置请求；服务端冻结完整配置快照后入队 SET_YOLO。 */
@Data
public class HarnessThreadYoloSetDTO {
  private Long expectedExecutionEpoch;

  /** 是否启用 YOLO（自动批准工具）。 */
  private Boolean yoloEnabled;

  /** 外部请求幂等键。 */
  private String clientMessageId;
}
