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

  private final String filename;
  private final String contentType;
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
