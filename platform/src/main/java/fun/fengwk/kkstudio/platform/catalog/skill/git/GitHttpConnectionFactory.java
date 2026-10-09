package fun.fengwk.kkstudio.platform.catalog.skill.git;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 每次 Git 操作独立的 HTTP factory：连接与真实 socket 读取空闲分别保护，无整次 deadline。 Proxy 由 JGit 的默认 ProxySelector
 * 选择；关闭和线程中断都会断开本次操作的全部连接。
 */
final class GitHttpConnectionFactory implements HttpConnectionFactory, AutoCloseable {
  static final int CONNECT_MILLIS = 60_000;
  static final int READ_MILLIS = 180_000;
  private final int connectMillis;
  private final int readMillis;
  private final Thread owner = Thread.currentThread();
  private final List<HttpURLConnection> connections = new CopyOnWriteArrayList<>();
  private Thread cancellation;
  private volatile boolean closed;

  GitHttpConnectionFactory() {
    this(CONNECT_MILLIS, READ_MILLIS);
  }

  GitHttpConnectionFactory(int connectMillis, int readMillis) {
    if (connectMillis <= 0 || readMillis <= 0) {
      throw new IllegalArgumentException("Git network timeouts must be positive");
    }
    this.connectMillis = connectMillis;
    this.readMillis = readMillis;
  }

  TransportConfigCallback callback() {
    return transport -> {
      String scheme = transport.getURI() == null ? null : transport.getURI().getScheme();
      String normalized = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
      if ("http".equals(normalized) || "https".equals(normalized)) {
        if (transport instanceof TransportHttp http) {
          http.setHttpConnectionFactory(this);
        }
        return;
      }
      if ("file".equals(normalized)) {
        // 本地 file 仓库不需要网络代理或 socket 超时。
        return;
      }
      // 未被 connect/read 网络保护覆盖的 Git transport：fail-closed，不留无限等待旁路。
      throw new UnsupportedTransportException(
          "unsupported Git transport scheme: " + (normalized == null ? "(none)" : normalized));
    };
  }

  /** 未被网络保护覆盖的 Git transport；调用方必须转换为稳定错误而不是继续等待。 */
  static final class UnsupportedTransportException extends RuntimeException {
    UnsupportedTransportException(String message) {
      super(message);
    }
  }

  @Override
  public HttpConnection create(URL url) throws IOException {
    return create(url, null);
  }

  @Override
  public HttpConnection create(URL url, Proxy proxy) throws IOException {
    checkCancelled();
    HttpURLConnection connection =
        (HttpURLConnection) (proxy == null ? url.openConnection() : url.openConnection(proxy));
    connections.add(connection);
    try {
      checkCancelled();
    } catch (InterruptedIOException error) {
      connection.disconnect();
      connections.remove(connection);
      throw error;
    }
    if (cancellation == null) {
      cancellation =
          Thread.ofVirtual()
              .name("git-network-cancellation")
              .start(
                  () -> {
                    try {
                      while (!closed && !owner.isInterrupted()) {
                        Thread.sleep(20);
                      }
                      if (owner.isInterrupted()) {
                        close();
                      }
                    } catch (InterruptedException ignored) {
                      // 正常完成时停止观察；close 已释放连接。
                    }
                  });
    }
    return new Connection(connection);
  }

  private void checkCancelled() throws InterruptedIOException {
    if (closed || owner.isInterrupted()) {
      throw new InterruptedIOException("Git network operation cancelled");
    }
  }

  @Override
  public void close() {
    closed = true;
    for (HttpURLConnection connection : connections) {
      connection.disconnect();
    }
    connections.clear();
    if (cancellation != null && cancellation != Thread.currentThread()) {
      cancellation.interrupt();
    }
  }

  /** 稳定分类沿 JGit 异常链传播，不依赖其本地化错误文本。 */
  static String failureCode(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (isAuthenticationFailure(cause)) {
        return SkillGitException.CODE_AUTHENTICATION_FAILED;
      }
      if (cause instanceof UnsupportedTransportException) {
        return "UNSUPPORTED_REPOSITORY_SCHEME";
      }
      if (cause instanceof SocketTimeoutException) {
        return "GIT_CONNECT_TIMEOUT".equals(cause.getMessage())
            ? "GIT_CONNECT_TIMEOUT"
            : "GIT_READ_TIMEOUT";
      }
    }
    return "GIT_FETCH_FAILED";
  }

  /** 仓库要求认证或被拒绝授权：必须产生明确指导，绝不作为“可能是公共仓库”的默认回退。 */
  static boolean isAuthenticationFailure(Throwable cause) {
    if (cause == null) {
      return false;
    }
    boolean transport =
        cause instanceof TransportException
            || (cause.getClass().getName().startsWith("org.eclipse.jgit.")
                && "TransportException".equals(cause.getClass().getSimpleName()));
    if (!transport) {
      return false;
    }
    for (Throwable current = cause; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message == null) {
        continue;
      }
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("not authorized")
          || lower.contains("authentication is required")
          || lower.contains("not permitted")
          || lower.contains("401")
          || lower.contains("403")) {
        return true;
      }
    }
    return false;
  }

  private final class Connection implements HttpConnection {
    private final HttpURLConnection connection;

    private Connection(HttpURLConnection connection) {
      this.connection = connection;
      setConnectTimeout(0);
      setReadTimeout(0);
    }

    private IOException failure(IOException error, String code) {
      connection.disconnect();
      if (error instanceof SocketTimeoutException) {
        SocketTimeoutException timeout = new SocketTimeoutException(code);
        timeout.initCause(error);
        return timeout;
      }
      return error;
    }

    @Override
    public void connect() throws IOException {
      try {
        checkCancelled();
        connection.connect();
        checkCancelled();
      } catch (IOException error) {
        throw failure(error, "GIT_CONNECT_TIMEOUT");
      }
    }

    @Override
    public int getResponseCode() throws IOException {
      connect();
      try {
        return connection.getResponseCode();
      } catch (IOException error) {
        throw failure(error, "GIT_READ_TIMEOUT");
      }
    }

    @Override
    public InputStream getInputStream() throws IOException {
      connect();
      try {
        return new FilterInputStream(connection.getInputStream()) {
          @Override
          public int read() throws IOException {
            try {
              return in.read();
            } catch (IOException error) {
              throw failure(error, "GIT_READ_TIMEOUT");
            }
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
              return in.read(bytes, offset, length);
            } catch (IOException error) {
              throw failure(error, "GIT_READ_TIMEOUT");
            }
          }

          @Override
          public void close() throws IOException {
            try {
              super.close();
            } finally {
              connection.disconnect();
              connections.remove(connection);
            }
          }
        };
      } catch (IOException error) {
        throw failure(error, "GIT_READ_TIMEOUT");
      }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      connect();
      try {
        return connection.getOutputStream();
      } catch (IOException error) {
        throw failure(error, "GIT_READ_TIMEOUT");
      }
    }

    // JGit 单一 setTimeout 不得覆盖分别设置的连接/空闲读取保护。
    @Override
    public void setConnectTimeout(int ignored) {
      connection.setConnectTimeout(connectMillis);
    }

    @Override
    public void setReadTimeout(int ignored) {
      connection.setReadTimeout(readMillis);
    }

    @Override
    public URL getURL() {
      return connection.getURL();
    }

    @Override
    public String getResponseMessage() throws IOException {
      return connection.getResponseMessage();
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return connection.getHeaderFields();
    }

    @Override
    public String getHeaderField(String name) {
      return connection.getHeaderField(name);
    }

    @Override
    public List<String> getHeaderFields(String name) {
      return connection.getHeaderFields().entrySet().stream()
          .filter(entry -> name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null)
          .flatMap(entry -> entry.getValue().stream())
          .toList();
    }

    @Override
    public String getContentType() {
      return connection.getContentType();
    }

    @Override
    public int getContentLength() {
      return connection.getContentLength();
    }

    @Override
    public String getRequestMethod() {
      return connection.getRequestMethod();
    }

    @Override
    public boolean usingProxy() {
      return connection.usingProxy();
    }

    @Override
    public void setRequestProperty(String key, String value) {
      connection.setRequestProperty(key, value);
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
      connection.setRequestMethod(method);
    }

    @Override
    public void setUseCaches(boolean useCaches) {
      connection.setUseCaches(useCaches);
    }

    @Override
    public void setInstanceFollowRedirects(boolean follow) {
      connection.setInstanceFollowRedirects(follow);
    }

    @Override
    public void setDoOutput(boolean output) {
      connection.setDoOutput(output);
    }

    @Override
    public void setFixedLengthStreamingMode(int length) {
      connection.setFixedLengthStreamingMode(length);
    }

    @Override
    public void setChunkedStreamingMode(int length) {
      connection.setChunkedStreamingMode(length);
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier verifier) {
      ((HttpsURLConnection) connection).setHostnameVerifier(verifier);
    }

    @Override
    public void configure(KeyManager[] keys, TrustManager[] trust, SecureRandom random)
        throws NoSuchAlgorithmException, KeyManagementException {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys, trust, random);
      ((HttpsURLConnection) connection).setSSLSocketFactory(context.getSocketFactory());
    }
  }
}
