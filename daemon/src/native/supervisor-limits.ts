/**
 * POSIX 与 Windows supervisor 共用的有界预算。
 *
 * <p>两套实现的准备、收敛、强杀与轮询步长必须一致，否则同一调用在不同平台会有不同的失败窗口。
 * Windows 没有单独的温和信号宽限，终止默认预算沿用收敛预算。
 */

/** 等待 helper 发布范围并确认的预算：覆盖 Node 冷启动。 */
export const PREPARE_BUDGET_MILLIS = 20_000

/** helper 退出后再确认执行范围收敛的预算。 */
export const CONVERGENCE_BUDGET_MILLIS = 4_000

/** POSIX 温和信号之后的默认宽限窗口。 */
export const DEFAULT_GRACE_MILLIS = 1_000

/** 强杀 helper 之后的等待预算。 */
export const FORCE_KILL_BUDGET_MILLIS = 1_000

/** 轮询步长。 */
export const POLL_INTERVAL_MILLIS = 5

/** 对外错误里附加的 helper 自述长度上限：单行短记录，绝不整段外泄。 */
export const SUPERVISOR_MESSAGE_LIMIT = 300
