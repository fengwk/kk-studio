package fun.fengwk.kkstudio.share.ai.catalog;

/**
 * Tool 与 Environment 的关系，Tool catalog 与 Thread Debug 共用同一个 wire 枚举。
 *
 * <p>用单一枚举表达三种互斥关系，而不是两个布尔字段：{@code NONE / OPTIONAL / REQUIRED} 的任意组合都是合法状态，客户端不需要拼接字段推断工具能否在无
 * Environment 时进入模型工具面。
 */
public enum EnvironmentSupportDTO {
  /** 不需要 Environment：始终在 Platform 执行。 */
  NONE,

  /** 可选：无 Environment 时按 Platform 能力执行，已选择时绑定当前 Environment。 */
  OPTIONAL,

  /** 必须有 Environment：Branch 未选择 Environment 时该 Tool 被过滤出模型工具面。 */
  REQUIRED
}
