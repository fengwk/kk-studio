package fun.fengwk.kkstudio.core.ai.environment.gateway;

/** Process-local transport handle for one Environment Daemon WebSocket connection. */
public interface EnvironmentDaemonConnection {

  /** Stable only for this transport connection; not a durable Environment identity. */
  String connectionId();

  boolean isOpen();

  /** Sends one fully encoded daemon protocol text frame. */
  void sendText(String text);

  void close();
}
