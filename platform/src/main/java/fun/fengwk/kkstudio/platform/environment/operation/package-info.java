package fun.fengwk.kkstudio.platform.environment.operation;

/**
 * Platform 独占的 {@code environment_operation} 持久模型与 PostgreSQL 原子状态机。
 *
 * <p>本包提供跨节点管理操作信箱与不可变历史的持久化访问。 核心安全边界：
 *
 * <ul>
 *   <li>私有 {@code arguments} 是冻结的执行参数（Local MCP 发现时含本地 Server 配置，可能含凭据）， 仅在内部 dispatcher
 *       流程中可访问；其内容严格排除在模型 {@code toString()}、所有公开投影、日志与异常之外。
 *   <li>所有生命周期状态转换均由 PostgreSQL 原子 SQL 与 {@code statement_timestamp()} 围栏保证， 杜绝 JVM-DB
 *       时钟竞争与陈旧节点并发覆写。
 * </ul>
 */
