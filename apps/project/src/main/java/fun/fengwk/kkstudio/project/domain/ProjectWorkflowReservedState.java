package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;

/**
 * workflow 固定保留的状态编码：INIT、BLOCKED、DONE。
 *
 * <p>三者必须出现在每个 workflow 中，且只能表达保留语义：INIT 是正常边起点，BLOCKED 由专用阻塞/恢复操作进入、 不配置普通边，DONE 由显式重开操作回到
 * INIT。其余编码都是工作阶段。
 */
public enum ProjectWorkflowReservedState {
  /** 待开始：Issue 创建后的正常边起点。 */
  INIT,

  /** 业务阻塞：由专用阻塞/恢复操作进入，记录原阶段与原因。 */
  BLOCKED,

  /** 完成：正常边的终点，只能由显式重开操作回到 INIT。 */
  DONE;

  /** 保留编码对应的状态编码值。 */
  public ProjectStateCode code() {
    return ProjectStateCode.of(name());
  }

  /** 判定自然状态编码是否为固定保留编码。 */
  public static boolean isReserved(ProjectStateCode code) {
    Objects.requireNonNull(code, "code");
    for (ProjectWorkflowReservedState reserved : values()) {
      if (reserved.code().equals(code)) {
        return true;
      }
    }
    return false;
  }
}
