package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * 工具结果内容增量。
 *
 * @author fengwk
 */
@Data
public class ToolContentDelta {

  /** 内容类型。 */
  private ToolContentType type;

  /** 文本增量。 */
  private String text;

  /** 媒体内容数据。 */
  private String data;

  /** 媒体 mime 类型。 */
  private String mime;

  /** 媒体展示名。 */
  private String name;
}
