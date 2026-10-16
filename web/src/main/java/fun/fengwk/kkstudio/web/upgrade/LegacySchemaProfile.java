package fun.fengwk.kkstudio.web.upgrade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 离线转换的 schema 身份与迁移历史门禁：对 PostgreSQL 目录做确定性规范化后取 SHA-256，并用三份真实 DDL 的固定指纹与 Flyway 校验和核准源与目标。
 *
 * <p>指纹覆盖 public canonical 应用 schema 的全部可观察形态：关系集合与
 * kind/persistence/partition/RLS、列的序号/名称/类型/可空/default/generated/identity/collation、所有
 * PK/UNIQUE/CHECK/FK 定义与 validated/deferrable/deferred、索引定义与有效性、非内部 trigger 与 rule、独立用户类型（除隐式数组与
 * relation 行类型之外的 public 全部 {@code pg_type}）、额外 sequence/view/routine、未被上述专用查询覆盖的其它 public schema
 * 对象（collation / operator / operator class 等，按真实 namespace 依赖与 {@code pg_identify_object} 稳定身份）、非内置
 * extension（无论装在哪个 namespace）、非 public 用户 namespace。
 *
 * <p>只有 Flyway 工具自己的 {@code flyway_schema_history} 表及其索引被排除（按表名精确排除，绝不用前缀匹配吞掉未知业务表）；FK 的 RI trigger
 * 只以「状态计数 + 状态多重集」参与其约束行，绝不把 trigger 名或 OID 放进指纹。因此任何未列入白名单的业务表、未知用户类型或被人为改动的 RI trigger
 * 状态都会改变指纹并被拒绝。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的 schema 门禁，不是平台 API。
 */
final class LegacySchemaProfile {

  /** DEV 基线（{@code 8126841e15cc166fa65f1de90c7313598f10044e}）的固定指纹。 */
  static final String DEV = "a849aa49aa8bbfa734067e988fa74ad192687eafbff6b78615ec31018ec8c426";

  /** MAIN 基线（{@code cb36799384c60f15119439276a8656efe303dc68}）的固定指纹。 */
  static final String MAIN = "3bc3946a899f87fc770b8f360dcf6bc11f75781319005e5135b934f61db093e3";

  /** 当前目标 V1（{@code f990606a115736e1e0a0ee411000e77c1174ae3d}）的固定指纹。 */
  static final String TARGET = "494eef80ad801d5067478ddb06e766c3c8a459934368f0575ef1f6094347daac";

  /** DEV 基线 V1 的真实 Flyway 校验和（Flyway 11.14.1 对同一份 DDL 的固定计算）。 */
  static final int DEV_V1_CHECKSUM = 554066052;

  /** MAIN 基线 V1 的真实 Flyway 校验和（Flyway 11.14.1 对同一份 DDL 的固定计算）。 */
  static final int MAIN_V1_CHECKSUM = -1855380029;

  /** 当前目标 V1 的真实 Flyway 校验和（Flyway 11.14.1 对同一份 DDL 的固定计算）。 */
  static final int TARGET_V1_CHECKSUM = 113176549;

  /** 两边共有的 41 张应用表；这是本切片唯一的复制清单事实源。 */
  static final List<String> SHARED_TABLES =
      List.of(
          "agent_definition",
          "agent_model",
          "agent_provider",
          "canvas_command_dedup",
          "canvas_document",
          "canvas_function_resource_pin",
          "canvas_function_run",
          "canvas_group",
          "canvas_node",
          "canvas_resource",
          "chat",
          "chat_session",
          "environment",
          "environment_connection",
          "environment_update_operation",
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_thread_join",
          "harness_thread_stop_receipt",
          "harness_tool_invocation",
          "harness_work",
          "mcp_server",
          "mcp_tool",
          "plugin_credential",
          "project",
          "project_issue",
          "project_issue_activity",
          "project_issue_agent_thread",
          "project_issue_evidence",
          "project_issue_run",
          "project_issue_stage_budget",
          "project_issue_work",
          "session_blob_ref",
          "skill_package",
          "storage_blob",
          "storage_object_cleanup",
          "storage_upload",
          "system_setting");

  /** 目标新增、只按真实 epoch0→1 初始化的目录/认证表；绝不复制或杜撰旧目录历史。 */
  static final List<String> TARGET_ONLY_TABLES =
      List.of(
          "plugin_auth_attempt",
          "plugin_catalog_revision",
          "plugin_catalog_state",
          "plugin_configuration_revision");

  /** 唯一被排除的工具表：Flyway 自己的历史表；它的 PK 索引/序列/类型都随该表一并排除。 */
  private static final String FLYWAY_TABLE = "flyway_schema_history";

  private static final Set<String> SHARED_TABLE_SET = Set.copyOf(SHARED_TABLES);

  private static final String RELATIONS =
      """
      select c.relname, c.relkind, c.relpersistence, c.relispartition,
             c.relrowsecurity, c.relforcerowsecurity
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public'
        and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')
        and c.relname <> '%s'
      order by c.relname
      """
          .formatted(FLYWAY_TABLE);

  private static final String COLUMNS =
      """
      select c.relname, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod),
             a.attnotnull, coalesce(pg_get_expr(ad.adbin, ad.adrelid), ''),
             a.attgenerated, a.attidentity,
             coalesce((select co.collname from pg_collation co where co.oid = a.attcollation), '')
      from pg_attribute a
      join pg_class c on c.oid = a.attrelid
      join pg_namespace n on n.oid = c.relnamespace
      left join pg_attrdef ad on ad.adrelid = a.attrelid and ad.adnum = a.attnum
      where n.nspname = 'public'
        and c.relkind in ('r', 'p', 'v', 'm', 'f')
        and a.attnum > 0
        and not a.attisdropped
        and c.relname <> '%s'
      order by c.relname, a.attnum
      """
          .formatted(FLYWAY_TABLE);

  /**
   * 约束行只带 RI trigger 的「数量 + 状态多重集」：单一 enabled 状态被改成 Replica / disabled，或新增第二个内部 trigger，都会改变这一列。
   */
  private static final String CONSTRAINTS =
      """
      select c.relname, con.conname, con.contype, pg_get_constraintdef(con.oid),
             con.condeferrable, con.condeferred, con.convalidated,
             coalesce(
               (select count(*)::text || '/' || coalesce(string_agg(t.tgenabled::text, ',' order by t.tgenabled::text), '')
                from pg_trigger t where t.tgconstraint = con.oid and t.tgisinternal), '')
      from pg_constraint con
      join pg_class c on c.oid = con.conrelid
      join pg_namespace n on n.oid = con.connamespace
      where n.nspname = 'public'
        and c.relname <> '%s'
      order by c.relname, con.conname
      """
          .formatted(FLYWAY_TABLE);

  private static final String INDEXES =
      """
      select c.relname, i.relname, pg_get_indexdef(x.indexrelid),
             x.indisunique, x.indisprimary, x.indisvalid, x.indisready, x.indisexclusion
      from pg_index x
      join pg_class i on i.oid = x.indexrelid
      join pg_class c on c.oid = x.indrelid
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public'
        and c.relname <> '%s'
      order by c.relname, i.relname
      """
          .formatted(FLYWAY_TABLE);

  private static final String TRIGGERS =
      """
      select c.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid)
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public'
        and not t.tgisinternal
        and c.relname <> '%s'
      order by c.relname, t.tgname
      """
          .formatted(FLYWAY_TABLE);

  private static final String RULES =
      """
      select c.relname, r.rulename, pg_get_ruledef(r.oid)
      from pg_rewrite r
      join pg_class c on c.oid = r.ev_class
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public'
        and r.rulename <> '_RETURN'
        and c.relname <> '%s'
      order by c.relname, r.rulename
      """
          .formatted(FLYWAY_TABLE);

  private static final String ROUTINES =
      """
      select p.proname, pg_get_function_identity_arguments(p.oid), p.prokind
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
      where n.nspname = 'public'
      order by p.proname, 2
      """;

  /**
   * public 下的独立用户类型：不按类型种类白名单筛选，因此 enum / domain / 独立 composite / range / multirange 之外的 base 与未定义
   * shell type 同样进入指纹。
   *
   * <p>只按真实 catalog 链接排除两类隐式类型：由 {@code pg_type.typarray} 指向的数组伴生类型，以及已经由 relation 行覆盖的行类型。
   */
  private static final String USER_TYPES =
      """
      select t.typname, t.typtype, t.typcategory
      from pg_type t
      join pg_namespace n on n.oid = t.typnamespace
      left join pg_class c on c.oid = t.typrelid
      where n.nspname = 'public'
        and (t.typrelid = 0 or c.relkind = 'c')
        and not exists (select 1 from pg_type element where element.typarray = t.oid)
      order by t.typname
      """;

  /**
   * public 下未被前述专用查询覆盖的其它 schema 对象：collation、operator、operator class/family、text search 对象等。
   *
   * <p>枚举依据是 PostgreSQL 自己记录的「对象 → public namespace」依赖，身份用 {@code pg_identify_object} 的稳定标识（类型 +
   * 限定名/签名），绝不使用 OID；已由 relation/index/type/routine/constraint 专门查询覆盖的类在这里排除。规范化顺序覆盖投影出的全部字段。
   */
  private static final String SCHEMA_OBJECTS =
      """
      select o.type, o.identity
      from pg_depend d
      join pg_namespace n on n.oid = d.refobjid
      cross join lateral pg_identify_object(d.classid, d.objid, d.objsubid) o
      where d.refclassid = 'pg_namespace'::regclass
        and n.nspname = 'public'
        and d.classid not in ('pg_class'::regclass, 'pg_type'::regclass, 'pg_proc'::regclass,
                              'pg_constraint'::regclass)
      order by o.type, o.identity
      """;

  /**
   * 非内置 extension：上面所有查询都只看 public namespace，而 extension 可以装进任何 namespace（装进 {@code pg_catalog}
   * 时连它自己的对象都不出现在 public），因此必须单独按 extension 目录核对。
   *
   * <p>只排除每个数据库都有的内置 {@code plpgsql}，按 {@code extname} 稳定排序。
   */
  private static final String EXTENSIONS =
      """
      select extname from pg_extension
      where extname <> 'plpgsql'
      order by extname
      """;

  private static final String NAMESPACES =
      """
      select nspname from pg_namespace
      where nspname <> 'public'
        and nspname <> 'information_schema'
        and nspname not like 'pg\\_%%'
      order by nspname
      """;

  private static final String CROSS_TABLE_FKS =
      """
      select cc.relname, pc.relname
      from pg_constraint con
      join pg_class cc on cc.oid = con.conrelid
      join pg_class pc on pc.oid = con.confrelid
      join pg_namespace n on n.oid = con.connamespace
      where con.contype = 'f'
        and n.nspname = 'public'
        and con.conrelid <> con.confrelid
      order by 1, 2
      """;

  private static final String HISTORY =
      "select version, checksum, success, type from flyway_schema_history order by installed_rank";

  private LegacySchemaProfile() {}

  /**
   * 计算一份连接的 public canonical 应用 schema 指纹。
   *
   * @throws LegacyDatabaseUpgradeException 目录读取失败时（固定安全原因）
   */
  static String fingerprint(Connection connection) {
    List<String> lines = new ArrayList<>();
    try {
      append(connection, lines, "relation", RELATIONS);
      append(connection, lines, "column", COLUMNS);
      append(connection, lines, "constraint", CONSTRAINTS);
      append(connection, lines, "index", INDEXES);
      append(connection, lines, "trigger", TRIGGERS);
      append(connection, lines, "rule", RULES);
      append(connection, lines, "routine", ROUTINES);
      append(connection, lines, "type", USER_TYPES);
      append(connection, lines, "schema-object", SCHEMA_OBJECTS);
      append(connection, lines, "extension", EXTENSIONS);
      append(connection, lines, "namespace", NAMESPACES);
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.SOURCE_PROFILE,
          "cannot read the application schema catalog");
    }
    return sha256Hex(String.join("\n", lines) + "\n");
  }

  /** 把源指纹识别为固定的 DEV / MAIN profile；任何其它值都不是已知旧部署。 */
  static String identify(String fingerprint) {
    if (DEV.equals(fingerprint)) {
      return "dev";
    }
    if (MAIN.equals(fingerprint)) {
      return "main";
    }
    throw new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.SOURCE_PROFILE,
        "legacy source schema is not a supported deployment profile");
  }

  /** 要求目标恰好是当前 V1 应用 schema，并且带着当前唯一成功的 V1 迁移历史。 */
  static void requireTarget(Connection connection) {
    if (!TARGET.equals(fingerprint(connection))) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_SCHEMA,
          "target schema is not the current application baseline");
    }
    requireBaselineHistory(
        connection, LegacyDatabaseUpgradeException.Stage.TARGET_SCHEMA, TARGET_V1_CHECKSUM, false);
  }

  /**
   * 校验源端的 Flyway 历史：只接受恰好一个成功的 versioned V1，且校验和与该 profile 的真实 DDL 一致；拒绝失败记录与其它 versioned 迁移。成功的
   * repeatable 数据 seed 只作为来源事实保留在源端，其历史绝不复制进目标。
   */
  static void requireSourceHistory(Connection connection, String profile) {
    requireBaselineHistory(
        connection,
        LegacyDatabaseUpgradeException.Stage.SOURCE_HISTORY,
        "main".equals(profile) ? MAIN_V1_CHECKSUM : DEV_V1_CHECKSUM,
        true);
  }

  /**
   * 一份 Flyway 基线历史的唯一合法形态：恰好一条成功的 versioned V1（type {@code SQL}，校验和与真实 DDL 一致），没有失败记录、没有
   * UNDO/DELETE 等非增量记录、没有其它 versioned 版本；repeatable 记录只在显式允许时作为来源 seed 事实存在。
   */
  private static void requireBaselineHistory(
      Connection connection,
      LegacyDatabaseUpgradeException.Stage stage,
      int expectedChecksum,
      boolean allowRepeatableSeeds) {
    int versioned = 0;
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(HISTORY)) {
      while (rows.next()) {
        String version = rows.getString(1);
        boolean success = rows.getBoolean(3);
        String type = rows.getString(4);
        if (!success) {
          throw failure(stage, "the migration history carries a failed migration");
        }
        if ("UNDO".equals(type) || "DELETE".equals(type)) {
          throw failure(stage, "the migration history carries a non-additive migration record");
        }
        if (version == null) {
          if (!allowRepeatableSeeds) {
            throw failure(
                stage, "the migration history carries an unexpected repeatable migration");
          }
          continue;
        }
        if (!"1".equals(version.strip()) || !"SQL".equals(type)) {
          throw failure(stage, "the migration history carries an unsupported versioned migration");
        }
        versioned++;
        Integer checksum = (Integer) rows.getObject(2);
        if (checksum == null || checksum != expectedChecksum) {
          throw failure(
              stage, "the baseline migration does not match the expected application schema");
        }
      }
    } catch (SQLException error) {
      throw failure(stage, "cannot read the migration history");
    }
    if (versioned != 1) {
      throw failure(
          stage, "the migration history must carry exactly one successful versioned baseline");
    }
  }

  /**
   * 目标共有表的稳定 parent-first 顺序：只按 target 跨表 FK 分层（self-FK 不参与），同层按表名升序。
   *
   * @throws LegacyDatabaseUpgradeException 目标 FK 图出现环时
   */
  static List<String> parentFirstOrder(Connection target) {
    Map<String, Set<String>> parents = new TreeMap<>();
    for (String table : SHARED_TABLES) {
      parents.put(table, new TreeSet<>());
    }
    try (Statement statement = target.createStatement();
        ResultSet rows = statement.executeQuery(CROSS_TABLE_FKS)) {
      while (rows.next()) {
        String child = rows.getString(1);
        String parent = rows.getString(2);
        if (SHARED_TABLE_SET.contains(child) && SHARED_TABLE_SET.contains(parent)) {
          parents.get(child).add(parent);
        }
      }
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_SCHEMA,
          "cannot read the target foreign key topology");
    }
    Map<String, Integer> layers = new LinkedHashMap<>();
    boolean progressed = true;
    while (layers.size() < parents.size() && progressed) {
      progressed = false;
      for (Map.Entry<String, Set<String>> entry : parents.entrySet()) {
        if (layers.containsKey(entry.getKey())) {
          continue;
        }
        int layer = 0;
        boolean ready = true;
        for (String parent : entry.getValue()) {
          Integer parentLayer = layers.get(parent);
          if (parentLayer == null) {
            ready = false;
            break;
          }
          layer = Math.max(layer, parentLayer + 1);
        }
        if (ready) {
          layers.put(entry.getKey(), layer);
          progressed = true;
        }
      }
    }
    if (layers.size() != parents.size()) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_SCHEMA,
          "target foreign key graph is not acyclic");
    }
    List<String> ordered = new ArrayList<>(SHARED_TABLES);
    ordered.sort(
        (left, right) -> {
          int byLayer = Integer.compare(layers.get(left), layers.get(right));
          return byLayer != 0 ? byLayer : left.compareTo(right);
        });
    return List.copyOf(ordered);
  }

  private static LegacyDatabaseUpgradeException failure(
      LegacyDatabaseUpgradeException.Stage stage, String reason) {
    return new LegacyDatabaseUpgradeException(stage, reason);
  }

  private static void append(Connection connection, List<String> lines, String tag, String sql)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      int columns = rows.getMetaData().getColumnCount();
      StringBuilder line = new StringBuilder(256);
      while (rows.next()) {
        line.setLength(0);
        line.append(tag);
        for (int index = 1; index <= columns; index++) {
          line.append('\t').append(escape(rows.getString(index)));
        }
        lines.add(line.toString());
      }
    }
  }

  /** 转义制表符/换行/回车/反斜线，使单行编码无歧义；null 以未转义的 {@code \N} 表达，与字面量区分。 */
  private static String escape(String value) {
    if (value == null) {
      return "\\N";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '\t' -> escaped.append("\\t");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }

  private static String sha256Hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(Character.forDigit((value >> 4) & 0xF, 16));
        hex.append(Character.forDigit(value & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available");
    }
  }
}
