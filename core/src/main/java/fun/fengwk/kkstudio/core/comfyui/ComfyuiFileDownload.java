package fun.fengwk.kkstudio.core.comfyui;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 经任务输出元数据解析后的 ComfyUI 文件下载结果。
 *
 * @author fengwk
 */
@AllArgsConstructor
@Getter
public class ComfyuiFileDownload {

  private final String filename;
  private final String contentType;
  private final byte[] bytes;
}
