/**
 * Session 与 SessionEvent 的持久化仓储边界。
 *
 * <p>具体实现需要保证基础存储契约，例如 sessionId 过滤正确、eventId 唯一、写入事件结构完整。</p>
 */
package fun.fengwk.kkstudio.agent.session.repo;
