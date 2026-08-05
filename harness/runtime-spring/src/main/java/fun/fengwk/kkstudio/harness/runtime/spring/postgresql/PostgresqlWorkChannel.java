package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

/** Shared PostgreSQL LISTEN/NOTIFY channel for Harness Work availability hints. */
final class PostgresqlWorkChannel {

  static final String NAME = "harness_runtime_work";

  private PostgresqlWorkChannel() {}
}
