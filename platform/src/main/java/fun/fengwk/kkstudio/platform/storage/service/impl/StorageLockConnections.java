package fun.fengwk.kkstudio.platform.storage.service.impl;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 专用锁连接来源：每次 {@link #open()} 返回一条全新的物理会话。
 *
 * <p>实现绝不能借用业务连接池的连接：持锁连接在锁存续期间被占用，若来自业务池会耗尽池容量、让需要新连接的嵌套事务永久等待。连接信息属敏感数据， 实现与调用方都不得打印 URL 或凭据。
 *
 * @author fengwk
 */
@FunctionalInterface
public interface StorageLockConnections {

  Connection open() throws SQLException;
}
