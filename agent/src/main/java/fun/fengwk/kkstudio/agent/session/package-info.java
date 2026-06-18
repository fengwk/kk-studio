/**
 * Session tree 的核心模型与协调入口。
 *
 * <p>本包只表达会话树的结构事实：Session 表示元数据，Branch 表示逻辑游标，
 * SessionEvent 表示 append-only 的持久化事件，SessionManager 负责按 branch 读写事件链。</p>
 */
package fun.fengwk.kkstudio.agent.session;
