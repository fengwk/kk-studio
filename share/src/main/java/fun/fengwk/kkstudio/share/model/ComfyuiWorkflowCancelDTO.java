package fun.fengwk.kkstudio.share.model;

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

  private String runId;
  private boolean cancelled;
}
