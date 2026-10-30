/** 插件间共享的纯辅助：只依赖 JDK 和公开插件契约，不持有宿主实现或插件业务策略。 响应体使用调用方在发送前冻结的 nanoTime 截止点，错误不携带底层敏感消息。 */
package fun.fengwk.kkstudio.platform.plugin.support;
