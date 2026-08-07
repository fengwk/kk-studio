package fun.fengwk.kkstudio.share.comfyui;

import lombok.Builder;
import lombok.Data;

/**
 * ComfyUI 任务取消结果。
 *
 * @author fengwk
 */
@Builder
@Data
public class ComfyuiWorkflowCancelDTO {

  /** 被取消的 runId（回显请求入参，即 ComfyUI prompt/job id）。 */
  private String runId;

  /** 上游取消操作是否成功。 */
  private boolean cancelled;
}
