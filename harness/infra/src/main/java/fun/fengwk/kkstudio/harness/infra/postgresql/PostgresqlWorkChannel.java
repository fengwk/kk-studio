package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;

/** Harness Work availability hints 共享的 PostgreSQL LISTEN/NOTIFY channel。 */
final class PostgresqlWorkChannel {

  static final String NAME = HarnessWorkDispatcher.CHANNEL;

  private PostgresqlWorkChannel() {}
}
