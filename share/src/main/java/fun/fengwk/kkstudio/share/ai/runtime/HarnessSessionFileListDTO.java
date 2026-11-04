package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/** 会话文件面板的目录清单：一次只列一个会话目录。 */
@Data
public class HarnessSessionFileListDTO {

  /** 会话 ID（canonical UUID 文本）。 */
  private String sessionId;

  /** 被列出的目录，使用 {@code vfs:/sessions/<sessionId>/...} 坐标。 */
  private String path;

  /** 目录直属条目，按名称排序。 */
  private List<HarnessSessionFileEntryDTO> entries;
}
