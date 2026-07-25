package fun.fengwk.kkstudio.core.environment.gateway;

/**
 * Narrow Core transport endpoint for Environment daemon connections.
 *
 * <p>WebSocket adapters depend on this interface only. Protocol ownership and durable remote tool
 * transport remain with the Gateway implementation.
 */
public interface EnvironmentDaemonEndpoint {

  /** Registers a newly opened transport before its first HELLO frame arrives. */
  void open(EnvironmentDaemonConnection connection);

  /**
   * Processes one inbound text frame. Protocol decode/sequence validation may hold connection
   * state; ToolExecutionListener and READY dispatch always run after locks are released.
   */
  void receive(String connectionId, String rawMessage);

  /** Drops a transport handle; active remotes are notified as outcome-uncertain. */
  void close(String connectionId);
}
