package fun.fengwk.kkstudio.notification;

/** Sending in a bound physical transaction must use that transaction and propagate SQL failure. */
interface CrossNodeTransport extends AutoCloseable {
  void send(WireMessage message, boolean transactional);

  void start();

  boolean healthy();

  @Override
  void close();
}
