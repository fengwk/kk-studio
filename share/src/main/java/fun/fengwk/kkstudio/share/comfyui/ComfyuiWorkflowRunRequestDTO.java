package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

import java.util.Map;

/**
 * ComfyUI 工作流运行请求。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowRunRequestDTO {

  private Map<String, Object> parameters;
  private Map<String, ComfyuiWorkflowRunFileDTO> files;
}
