package fun.fengwk.kkstudio.platform.environment.skill;

/** 来源操作结果围栏发布的终态判定结果。 */
public enum OperationPublishOutcome {

  /** 成功持久化 Skill inventory 并原子终结操作为 SUCCEEDED 或 FAILED。 */
  APPLIED,

  /** 当前租约仍然有效，但配置代际或来源版本已发生变化，操作已推进为 FAILED (RESOURCE_CHANGED)。 */
  RESOURCE_CHANGED,

  /** 节点租约已丢失或过期，无操作并保持认领事实不动以供 UNKNOWN/timeout 收敛。 */
  LEASE_LOST
}
