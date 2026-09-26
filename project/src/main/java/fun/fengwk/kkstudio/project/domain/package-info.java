/**
 * Project 领域：workflow 配置（自然状态编码、正常边白名单与严格 JSON）、Issue+阶段执行额度、 Issue+Agent 稳定 Thread 身份，以及冻结历史区间的
 * Run 快照。
 *
 * <p>业务事实只用 JDK 类型表达，唯一外部依赖是 workflow 配置的 Jackson 严格编解码。需要跨行、跨 Session 或外部事实的判定（唯一活动 Run、Entry
 * 父链先后、Thread/Session 归属、Agent 与 Environment 是否仍然存在） 不属于这里，由持久化约束与 Runtime 事务负责。
 */
package fun.fengwk.kkstudio.project.domain;
