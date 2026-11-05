package fun.fengwk.kkstudio.share.catalog;

/**
 * Tool 与 Environment 的关系，Tool catalog 与 Thread Debug 共用同一个 wire 枚举。
 *
 * <p>用单一枚举表达三种互斥关系，而不是两个布尔字段：{@code NONE / ROUTED / REQUIRED} 的任意组合都是合法状态，客户端不需要拼接字段推断工具能否在无
 * Environment 时进入模型工具面。
 */
public enum EnvironmentSupportDTO {
  /** 不需要 Environment：始终在 Platform 执行。 */
  NONE,

  /** 逐调用路由：文件工具按每次调用的参数决定走 Platform VFS 还是绑定 Environment。 */
  ROUTED,

  /** 必须有 Environment：Branch 未选择 Environment 时该 Tool 被过滤出模型工具面。 */
  REQUIRED
}
