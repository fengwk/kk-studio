package fun.fengwk.kkstudio.share.model;

import lombok.Builder;
import lombok.Data;

/**
 * ComfyUI 工作流提交结果。
 *
 * @author fengwk
 */
@Builder
@Data
public class ComfyuiWorkflowRunDTO {

  private String runId;
  private String status;
  private String defaultSelector;
}
