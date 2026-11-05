package fun.fengwk.kkstudio.share.runtime;

import lombok.Data;

/** 会话文件面板的文本内容：有界窗口文本与文件的权威元数据。 */
@Data
public class HarnessSessionFileContentDTO {

  /** 会话 ID（canonical UUID 文本）。 */
  private String sessionId;

  /** 文件路径，使用 {@code vfs:/sessions/<sessionId>/...} 坐标。 */
  private String path;

  /** 文件完整字节数。 */
  private long sizeBytes;

  /** 文件 revision。 */
  private int revision;

  /** 最后更新时间（epoch 毫秒）。 */
  private long updatedAt;

  /** 从 {@code offset} 行、{@code columnOffset} 列开始的有界文本窗口。 */
  private String text;
}
