package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertFalse;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 离线装配的宿主探针：一个证明「没有打开数据库连接」的计数 DataSource，和一个证明「没有发出 HTTP」的本地回环探针。
 *
 * <p>两者都只观测事实，不模拟成功，也不替生产代码兜底。
 */
final class OfflineHostProbes {

  private OfflineHostProbes() {}

  /** 真实 materialized settings，只把 network 段指向本地探针：任何出网请求都会先连到探针。 */
  static SystemSettings settingsWithLocalProxy(String proxyUrl) {
    SystemSettings defaults = SystemSettings.DEFAULT;
    return new SystemSettings(
        defaults.tool(),
        defaults.aiRuntime(),
        defaults.environment(),
        new SystemSettings.Network(proxyUrl, ""),
        defaults.storageMedia(),
        defaults.advanced());
  }

  /** 计数 DataSource：离线装配本不该打开任何连接，因此任何连接尝试都直接失败并计数。 */
  static final class CountingDataSource implements DataSource {

    private final AtomicInteger openedConnections = new AtomicInteger();

    int openedConnections() {
      return openedConnections.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
      openedConnections.incrementAndGet();
      throw new SQLException("offline plugin catalog assembly must not open a database connection");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getLogger("offline-plugin-catalog-probe");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("offline plugin catalog probe is not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  /**
   * 本地出网探针：只统计到本进程回环端口的连接，从不代理或响应任何内容。
   *
   * <p>{@link #close()} 必须证明接收线程已经终止，否则「零命中」无法排除仍在途的连接；关闭错误与中断都直接抛出，由 try-with-resources 传播。
   */
  static final class LocalHttpProbe implements AutoCloseable {

    private static final long ACCEPTOR_JOIN_TIMEOUT_MILLIS = 5_000L;

    private final ServerSocket server;

    private final AtomicInteger connections = new AtomicInteger();

    private final Thread acceptor;

    private volatile IOException acceptFailure;

    private LocalHttpProbe(ServerSocket server) {
      this.server = server;
      this.acceptor = new Thread(this::acceptLoop, "offline-plugin-catalog-probe");
      this.acceptor.setDaemon(true);
    }

    static LocalHttpProbe start() throws IOException {
      LocalHttpProbe probe =
          new LocalHttpProbe(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
      probe.acceptor.start();
      return probe;
    }

    String proxyUrl() {
      return "http://127.0.0.1:" + server.getLocalPort();
    }

    int connections() {
      return connections.get();
    }

    private void acceptLoop() {
      while (!server.isClosed()) {
        try {
          Socket accepted = server.accept();
          connections.incrementAndGet();
          accepted.close();
        } catch (IOException error) {
          if (!server.isClosed()) {
            acceptFailure = error;
          }
          return;
        }
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      try {
        acceptor.join(ACCEPTOR_JOIN_TIMEOUT_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while stopping the offline HTTP probe", interrupted);
      }
      assertFalse(acceptor.isAlive(), "offline HTTP probe acceptor must terminate on close");
      if (acceptFailure != null) {
        throw acceptFailure;
      }
    }
  }
}
