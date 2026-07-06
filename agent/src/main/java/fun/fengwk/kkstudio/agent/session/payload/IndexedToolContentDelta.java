package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * 带槽位索引的工具内容增量。
 *
 * @author fengwk
 */
@Data
public class IndexedToolContentDelta {

  /** 工具结果内容在 content 列表中的槽位。 */
  private Integer index;

  /** 该槽位上的内容增量。 */
  private ToolContentDelta contentDelta;
}
