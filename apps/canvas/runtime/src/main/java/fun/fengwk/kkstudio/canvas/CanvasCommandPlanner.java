package fun.fengwk.kkstudio.canvas;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 纯领域命令规划：把一批 typed command 应用到画布权威快照上，产出持久化步骤、实体 patch 或具体冲突。
 *
 * <p>规划不触碰数据库与外部 I/O，因此并发语义可以独立测试：每条命令只比较自己语义组的前置条件，不同节点、同节点不同语义组互不覆盖； 存在冲突时整批不生效。同一画布的写入顺序由外层
 * document 行锁串行化，本类只表达在某一权威快照上的决策。
 *
 * <p>规划状态与资源槽位计算分别由 {@link CanvasCommandPlanning} 与 {@link CanvasResourceSlots} 承担。
 */
public final class CanvasCommandPlanner {

  /** 单个节点当前资源数组的长度上限，限制资源数量与 patch 体积。 */
  static final int MAX_RESOURCES_PER_NODE = 32;

  private final CanvasResourceSlots resourceSlots;

  /**
   * @param clock 新 Resource 的创建时间来源
   * @param resourceIds 新 Resource id 的受控分配来源（服务端生成）
   */
  public CanvasCommandPlanner(Clock clock, Supplier<UUID> resourceIds) {
    this.resourceSlots = new CanvasResourceSlots(clock, resourceIds);
  }

  /** 规划一个命令批；结构性非法请求抛 {@link fun.fengwk.kkstudio.common.error.DomainException}，语义组过期返回冲突。 */
  public CanvasCommandPlan plan(CanvasGraph graph, List<CanvasCommand> commands) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(commands, "commands");
    CanvasCommandPlanning planning = new CanvasCommandPlanning(graph, resourceSlots);
    for (CanvasCommand command : commands) {
      Objects.requireNonNull(command, "command");
      planning.apply(command);
    }
    planning.validate();
    return planning.toPlan();
  }
}
