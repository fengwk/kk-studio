package fun.fengwk.kkstudio.core.agent.support;

import fun.fengwk.convention4j.springboot.starter.snowflake.GlobalSnowflakeIdGenerator;

/**
 * Legacy Snowflake id bridge for the Harness adapters.
 *
 * <p><b>Do not add new callers.</b> Every non-Harness business id (provider, model, definition,
 * comfyui workflow api, canvas document/node/link/command, chat, chat session) now flows through
 * {@link fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator} and is allocated
 * from the {@code kk_studio_id_seq} sequence declared in {@code schema-postgresql.sql}.
 *
 * <p>The Harness adapters still reference this class through their {@code Snowflake*IdGenerator}
 * implementations until the Harness adapters themselves are migrated. The remaining methods are
 * preserved as a transitional bridge only and will be removed once that migration lands.
 *
 * @author fengwk
 */
public final class AgentIdGenerator {

  public static final String HARNESS_SESSION = "harness_session";
  public static final String HARNESS_SESSION_ENTRY = "harness_session_entry";
  public static final String HARNESS_THREAD = "harness_thread";
  public static final String HARNESS_THREAD_INPUT = "harness_thread_input";
  public static final String HARNESS_THREAD_STOP = "harness_thread_stop";
  public static final String HARNESS_THREAD_EVENT = "harness_thread_event";
  public static final String MODEL_USAGE_RECORD = "model_usage_record";

  private AgentIdGenerator() {}

  public static long nextHarnessSessionId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION);
  }

  public static long nextHarnessSessionEntryId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION_ENTRY);
  }

  public static long nextHarnessThreadId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD);
  }

  public static long nextHarnessThreadInputId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_INPUT);
  }

  public static long nextHarnessThreadStopId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_STOP);
  }

  public static long nextHarnessThreadEventId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_EVENT);
  }

  public static long nextModelUsageRecordId() {
    return GlobalSnowflakeIdGenerator.next(MODEL_USAGE_RECORD);
  }
}
