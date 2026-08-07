package fun.fengwk.kkstudio.core.comfyui;

import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 经任务输出元数据解析后的 ComfyUI 文件下载结果。
 *
 * @author fengwk
 */
@Getter
public final class ComfyuiFileDownload {

  /** 输出元数据中的文件名（basename，不含目录）。 */
  private final String filename;

  /** 内容类型：按文件名猜测（URLConnection.guessContentTypeFromName），无法猜测时为 application/octet-stream。 */
  private final String contentType;

  /** 文件内容字节：构造与 getter 均防御性拷贝，调用方修改不会影响内部状态。 */
  private final byte[] bytes;

  public ComfyuiFileDownload(String filename, String contentType, byte[] bytes) {
    this.filename = filename;
    this.contentType = contentType;
    this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
  }

  public byte[] getBytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }
}
