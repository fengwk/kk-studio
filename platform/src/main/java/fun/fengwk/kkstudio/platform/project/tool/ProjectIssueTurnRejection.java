package fun.fengwk.kkstudio.platform.project.tool;

/**
 * Issue Agent turn 的确定性业务拒绝信号。
 *
 * <p>只由显式业务门禁产生：Thread/Agent 归属不匹配、Issue 已归档/已暂停/不在可执行工作阶段、无活动 Run 或活动 Run 与当前 Thread/阶段
 * 不一致。DatabaseTurnResolver 把它收敛为 planning 失败（{@code PLANNING_FAILED}），绝不伪装成基础设施异常；相反，归属存在但
 * Issue/Project 行缺失等数据不一致必须抛 {@link IllegalStateException} 并原样传播。
 */
public final class ProjectIssueTurnRejection extends RuntimeException {

  public ProjectIssueTurnRejection(String message) {
    super(message);
  }
}
