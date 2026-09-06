package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

/**
 * ComfyUI 工作流运行的文件输入。
 *
 * <p>wire 契约仅包含 {@code blobId} 与客户端 {@code filename}，绝不接受 key 与 contentType。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowRunFileDTO {

  /** 目标 ACTIVE blob 的 canonical UUID 字符串。 */
  private String blobId;

  /** 上传到 ComfyUI 的基准文件名（严格 basename 校验）。 */
  private String filename;
}
