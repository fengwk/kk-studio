package fun.fengwk.kkstudio.web.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 源端排空守卫与源端事实读取：只在只读一致快照里检查，绝不 Stop、删行、改 phase/status 或删除残余事实。
 *
 * <p>排空判据与生产读路径同义：command 真正 QUEUED 是 {@code applied_entry_id is null and cancelled_at is
 * null}（因此已取消的历史命令不会被误判为排队）；Join 未结算与 task 未交付是两个独立谓词，compaction 的正常无 delivery 不算 task 未决；Canvas
 * 只接受源 V4 的终态 Run，拒绝 non-terminal、未知 version 或与 status 不一致的 stage。lease 边界一律以源事务时间为准。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的源端门禁，不是平台 API。
 */
final class LegacySourceGuard {

  private static final String THREADS_NOT_STOPPED =
      "select count(*) from harness_thread where execution_control <> 'STOPPED'";

  private static final String MODEL_INVOCATIONS = "select count(*) from harness_model_invocation";

  private static final String TOOL_INVOCATIONS = "select count(*) from harness_tool_invocation";

  private static final String HARNESS_WORK = "select count(*) from harness_work";

  private static final String PROJECT_ISSUE_WORK = "select count(*) from project_issue_work";

  private static final String QUEUED_COMMANDS =
      "select count(*) from harness_thread_command"
          + " where applied_entry_id is null and cancelled_at is null";

  private static final String INCOMPLETE_JOINS =
      "select count(*) from harness_thread_join"
          + " where terminal_entry_id is null and superseded_by_invocation_id is null";

  private static final String UNDELIVERED_TASK_JOINS =
      "select count(*) from harness_thread_join"
          + " where terminal_entry_id is not null"
          + " and delivery_command_sequence is null"
          + " and purpose = 'task'"
          + " and superseded_by_invocation_id is null";

  private static final String ACTIVE_ISSUE_RUNS =
      "select count(*) from project_issue_run where status in ('RUNNING', 'WAITING')";

  private static final String ACTIVE_ENVIRONMENT_OPERATIONS =
      "select count(*) from environment_update_operation"
          + " where phase in ('PENDING', 'RUNNING', 'PREPARED')";

  private static final String NON_TERMINAL_CANVAS_RUNS =
      """
      select count(*) from canvas_function_run
      where status not in ('SUCCEEDED', 'FAILED', 'CANCELLED')
         or jsonb_typeof(state_json -> 'version') is distinct from 'number'
         or state_json -> 'version' <> '4'::jsonb
         or state_json ->> 'stage' is distinct from status
      """;

  private static final String CREDENTIAL_LEASES =
      "select count(*) from plugin_credential"
          + " where refresh_lease_token is not null or refresh_lease_until is not null";

  /** 1.1.0 的交互认证 nonce 只存在于 pending attempt，非零即仍有未完成认证；绝不把 nonce 当作可复制历史。 */
  private static final String PENDING_AUTH_ATTEMPTS = "select count(*) from plugin_auth_attempt";

  /** 1.1.0 的目录指针必须存在；单行/epoch 引用完整性由 id=1 与即时外键约束保证。 */
  private static final String CATALOG_STATE_ROWS = "select count(*) from plugin_catalog_state";

  private static final String FUTURE_CONNECTION_LEASES =
      "select count(*) from environment_connection where lease_until > now()";

  private static final String FUTURE_UPLOAD_CLEANUP_LEASES =
      "select count(*) from storage_upload where cleanup_until > now()";

  private LegacySourceGuard() {}

  /** 源 system_setting 的完整单行事实：id、legacy 七节 config 原文、版本与两个应用侧时间。 */
  record SourceSettings(long id, String config, long version, String createdAt, String updatedAt) {}

  /** 要求源端已经真正排空：没有任何在执行的 Thread、执行事实、未决 Join、未交付 task、active run 或未到期 lease。 */
  static void requireDrained(Connection source, String profile) {
    requireZero(source, THREADS_NOT_STOPPED, "legacy source still has a runnable thread");
    requireZero(source, MODEL_INVOCATIONS, "legacy source still has model invocation facts");
    requireZero(source, TOOL_INVOCATIONS, "legacy source still has tool invocation facts");
    requireZero(source, HARNESS_WORK, "legacy source still has harness work");
    requireZero(source, PROJECT_ISSUE_WORK, "legacy source still has project issue work");
    requireZero(source, QUEUED_COMMANDS, "legacy source still has queued commands");
    requireZero(source, INCOMPLETE_JOINS, "legacy source still has unsettled thread joins");
    requireZero(source, UNDELIVERED_TASK_JOINS, "legacy source still has undelivered tasks");
    requireZero(source, ACTIVE_ISSUE_RUNS, "legacy source still has an active issue run");
    requireZero(
        source,
        ACTIVE_ENVIRONMENT_OPERATIONS,
        "legacy source still has an active environment update");
    requireZero(
        source, NON_TERMINAL_CANVAS_RUNS, "legacy source has a non-terminal canvas function run");
    requireZero(source, CREDENTIAL_LEASES, "legacy source still leases a plugin credential");
    requireZero(
        source, FUTURE_CONNECTION_LEASES, "legacy source still leases an environment connection");
    requireZero(
        source, FUTURE_UPLOAD_CLEANUP_LEASES, "legacy source still leases an upload cleanup");
    if (LegacySchemaProfile.isV110(profile)) {
      // 1.1.0 已有当前 catalog/nonce/凭据代际；直接沿用旧 41 表清单会漏掉这些事实。
      requireZero(source, PENDING_AUTH_ATTEMPTS, "legacy source still has a pending plugin auth");
      requireExactly(
          source, CATALOG_STATE_ROWS, 1, "legacy source catalog state is not the singleton row");
    }
  }

  /**
   * MAIN 的退役瞬时路由表只在其全部 lease 已到期时退休：不做任何源端清理，只回报退休行数，绝不导入目标。
   *
   * @throws LegacyDatabaseUpgradeException 表不存在或存在未到期 lease 时
   */
  static long retiredClusterNodeRows(Connection source) {
    if (count(
            source,
            "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = 'public' and c.relname = 'cluster_node' and c.relkind = 'r'")
        == 0) {
      return 0;
    }
    requireZero(
        source,
        "select count(*) from cluster_node where lease_until > now()",
        "legacy source still leases a cluster node");
    return count(source, "select count(*) from cluster_node");
  }

  /** 读取源 system_setting 的唯一 id=1 行；多行或形状不符都是不可安全升级的旧部署。 */
  static SourceSettings readSystemSetting(Connection source) {
    SourceSettings settings = null;
    int rows = 0;
    try (Statement statement = source.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select id, config::text, version, created_at::text, updated_at::text"
                    + " from system_setting")) {
      while (resultSet.next()) {
        rows++;
        settings =
            new SourceSettings(
                resultSet.getLong(1),
                resultSet.getString(2),
                resultSet.getLong(3),
                resultSet.getString(4),
                resultSet.getString(5));
      }
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN,
          "cannot read the legacy system settings");
    }
    if (rows != 1 || settings == null || settings.id() != 1L) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN,
          "legacy system settings must be the single canonical row");
    }
    return settings;
  }

  private static void requireZero(Connection source, String sql, String reason) {
    if (count(source, sql) != 0) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, reason);
    }
  }

  private static void requireExactly(Connection source, String sql, long expected, String reason) {
    if (count(source, sql) != expected) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, reason);
    }
  }

  private static long count(Connection source, String sql) {
    try (Statement statement = source.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN,
          "cannot evaluate a legacy source drain guard");
    }
  }
}
