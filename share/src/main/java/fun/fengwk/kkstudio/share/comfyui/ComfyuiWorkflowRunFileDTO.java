package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

/**
 * ComfyUI 工作流运行的 S3 文件输入。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowRunFileDTO {

  private String key;
  private String filename;
  private String contentType;
}
