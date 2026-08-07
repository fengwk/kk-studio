package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasDocumentDTO {
  /** 画布主键：正十进制字符串（底层 bigint，由数据库序列分配）。 */
  private String id;

  /** 展示标题：非空白（创建时空白输入会落为默认标题）。 */
  private String title;

  /** 乐观锁 revision：非负十进制字符串，每次命令应用 +1（CAS 依据）。 */
  private String revision;

  /** 默认视口 JSON（如 {@code {"x":80,"y":20,"scale":0.6}}）。 */
  private String homeViewportJson;
}
