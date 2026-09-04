/**
 * 一次 Model invocation 的持久化当前状态及其冻结的请求事实。
 *
 * <p>本包中的类型是作为 {@code harness_model_invocation} 当前状态持久化的领域值与状态；它们不是事件、不是 Event Sourcing 日志、也不是
 * repository 聚合。请求冻结了已解析的 provider 请求、tool/skill bindings、实际 Environment 路由以及 Thread YOLO
 * policy；retry 仅 replay 原始请求。调度租约与所有权围栏由 {@link fun.fengwk.kkstudio.harness.runtime.work} 包独占拥有。
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.model;
