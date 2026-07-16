package fun.fengwk.kkstudio.share.model;

import lombok.Builder;
import lombok.Data;

/**
 * ComfyUI 无状态任务查询结果。
 *
 * @author fengwk
 */
@Builder
@Data
public class ComfyuiWorkflowJobDTO {

  private String runId;
  private String status;
  private Double priority;
  private Long createTime;
  private Long updateTime;
  private String workflowId;
  private Long executionStartTime;
  private Long executionEndTime;
  private Integer outputsCount;
  private Object executionError;
  private Object executionStatus;
  private Object workflow;
  private Object previewOutput;
  private Object result;
}
