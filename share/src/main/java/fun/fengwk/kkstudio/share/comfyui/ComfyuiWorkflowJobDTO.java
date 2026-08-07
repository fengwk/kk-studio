package fun.fengwk.kkstudio.share.comfyui;

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

  /** 任务 id：即 ComfyUI prompt/job id（运行期无持久化）。 */
  private String runId;

  /**
   * 任务状态：上游 ComfyUI
   * 队列协议状态字符串原样透传；服务端识别终态集合（completed/complete/succeeded/success/failed/error/cancelled/canceled/interrupted）。
   */
  private String status;

  /** 任务优先级：上游协议原值（可空）。 */
  private Double priority;

  /** 任务创建时间：上游协议时间戳原样透传（可空）。 */
  private Long createTime;

  /** 任务更新时间：上游协议时间戳原样透传（可空）。 */
  private Long updateTime;

  /** 关联的 workflow id：上游协议原值（可空）。 */
  private String workflowId;

  /** 执行开始时间：上游协议时间戳原样透传（可空）。 */
  private Long executionStartTime;

  /** 执行结束时间：上游协议时间戳原样透传（可空）。 */
  private Long executionEndTime;

  /** 输出数量：上游协议原值（可空）。 */
  private Integer outputsCount;

  /** 执行错误：上游协议原始结构（JsonNode）原样透传（可空）。 */
  private Object executionError;

  /** 执行状态详情：上游协议原始结构原样透传（可空）。 */
  private Object executionStatus;

  /** workflow 快照：上游协议原始结构原样透传（可空）。 */
  private Object workflow;

  /** 预览输出：上游协议原始结构原样透传（可空）。 */
  private Object previewOutput;

  /** 归一化结果：仅终态非 null；未传 select 时为完整归一化结果，传 select 时为 JSONPath 选取的子集。 */
  private Object result;
}
