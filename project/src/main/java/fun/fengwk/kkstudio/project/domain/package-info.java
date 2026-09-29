/**
 * Project 领域：workflow 配置（自然状态编码、正常边白名单与严格 JSON）、阶段流转，以及 Issue+工作阶段的执行额度授权规则。
 *
 * <p>业务事实只用 JDK 类型表达，唯一外部依赖是 workflow 配置的 Jackson 严格编解码。需要跨行、跨 Session 或外部事实的判定（唯一活动 Run、Entry
 * 父链先后、Thread/Session 归属、Agent 与 Environment 是否仍然存在） 不属于这里，由 model 行、持久化约束与 Runtime 事务负责。
 */
package fun.fengwk.kkstudio.project.domain;
