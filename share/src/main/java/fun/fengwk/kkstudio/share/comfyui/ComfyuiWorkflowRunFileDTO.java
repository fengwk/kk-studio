package fun.fengwk.kkstudio.share.comfyui;

import lombok.Data;

/**
 * ComfyUI 工作流运行的 S3 文件输入。
 *
 * @author fengwk
 */
@Data
public class ComfyuiWorkflowRunFileDTO {

  /** S3 对象键：服务端统一校验（非空白、无前导 {@code '/'}、无 {@code .}/{@code ..} 段、无控制字符、UTF-8 ≤1024 字节）。 */
  private String key;

  /** 上传到 ComfyUI 的文件名（实际上传名取此值，而非 S3 key）。 */
  private String filename;

  /** 可空 media type；为空时依次回退 S3 对象 Content-Type 与默认 {@code application/octet-stream}。 */
  private String contentType;
}
