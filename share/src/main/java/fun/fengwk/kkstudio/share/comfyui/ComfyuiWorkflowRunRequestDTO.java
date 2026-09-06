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

  /** 可空参数映射：key 为输入 binding name，value 按 binding 的 valueType 强类型校验/转换；null 视作空映射。 */
  private Map<String, Object> parameters;

  /** 可空 Blob 文件输入映射：key 为输入 binding name，value 为文件描述；null 视作空映射。 */
  private Map<String, ComfyuiWorkflowRunFileDTO> files;
}
