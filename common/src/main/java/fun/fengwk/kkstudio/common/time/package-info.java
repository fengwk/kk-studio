/**
 * 跨模块复用的毫秒时间边界：{@link fun.fengwk.kkstudio.common.time.HarnessStoreTime} 是 durable 时间精度校验与毫秒 Clock
 * 包装的唯一入口。
 *
 * <p>本包只依赖 JDK，不承载时区、格式化或领域状态；哪些时间必须精确到毫秒、哪些 duration 表示延迟由调用方决定。
 */
package fun.fengwk.kkstudio.common.time;
