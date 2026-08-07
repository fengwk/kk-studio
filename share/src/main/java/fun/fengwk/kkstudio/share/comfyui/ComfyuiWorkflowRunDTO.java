package fun.fengwk.kkstudio.share.comfyui;

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

  /** 提交得到的 runId：即 ComfyUI prompt id（无状态，直接复用上游值）。 */
  private String runId;

  /** 提交后初始状态：固定为 {@code "pending"}。 */
  private String status;

  /** 持久化卡片配置的默认结果 selector（运行时直接透传）。 */
  private String defaultSelector;
}
