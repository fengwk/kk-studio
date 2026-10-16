package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link LegacyTableCopy} 运输层的真实 PostgreSQL 17 验收：原生 binary / text COPY 必须精确保留 jsonb 高精度数值、BYTEA、
 * TEXT[]、TIMESTAMPTZ(3)、NULL / 空串 / 字面 {@code \N} / 反斜线 / 制表符 / 回车 / 换行 / Unicode，并回报服务端确认为行数。
 *
 * <p>源库与目标库是两个独立数据库上的同名测试专用表：源库没有外键与唯一约束，目标库有真实即时外键与唯一索引。因此「故意不匹配」的源数据会让真实 COPY 以 23503 / 23505
 * 失败，随后连接可以真实回滚并继续复用——取消协议不是靠伪造 SQLException 或禁用约束证明的。
 *
 * <p>self-FK 探针在两端都启用即时外键，并以 child-first 物理顺序被复制，证明单语句 COPY 的语句末校验语义成立（逐行 INSERT 会被立即拒绝）。
 */
class LegacyTableCopyPostgresIntegrationTest {

  private static final String RESOURCE_ROOT = "fun/fengwk/kkstudio/web/upgrade/";

  private static final String PROBE_TABLE = "legacy_copy_probe";

  private static final String SELF_TABLE = "legacy_copy_probe_self";

  private static final List<String> PROBE_COLUMNS =
      List.of(
          "id",
          "parent_id",
          "unique_key",
          "json_payload",
          "blob_payload",
          "tags",
          "observed_at",
          "note");

  private static final String PARENT_ID = "00000000-0000-4000-8000-00000000a001";

  private static final String ROW_PARENTED = "00000000-0000-4000-8000-00000000b001";

  private static final String ROW_ORPHAN = "00000000-0000-4000-8000-00000000b002";

  private static final String ROW_BAD_PARENT = "00000000-0000-4000-8000-00000000b003";

  private static final String ROW_DUPLICATE_ONE = "00000000-0000-4000-8000-00000000b004";

  private static final String ROW_DUPLICATE_TWO = "00000000-0000-4000-8000-00000000b005";

  private static final String SELF_PARENT = "00000000-0000-4000-8000-00000000c001";

  private static final String SELF_CHILD = "00000000-0000-4000-8000-00000000c002";

  private static final String MISSING_PARENT = "00000000-0000-4000-8000-00000000a0ff";

  private static final String JSON_PAYLOAD =
      "{\"positiveExponent\":1e309,\"negativeExponent\":1e-324,"
          + "\"bigInteger\":123456789012345678901234567890,"
          + "\"beyondDoubleInteger\":9007199254740993,\"emptyText\":\"\",\"nullValue\":null}";

  private static final String TAG_LIST = "{alpha,\"beta,gamma\",中文}";

  private static final byte[] BLOB_PAYLOAD = {0x00, 0x01, (byte) 0xff, 0x10};

  private static final String OBSERVED_AT = "2024-05-06 07:08:09.123+00";

  /** 同时含字面 {@code \N}、反斜线、制表符、回车、换行与 Unicode：COPY text 转义必须逐字符可逆。 */
  private static final String TRICKY_NOTE =
      "literal\\N back\\slash tab\there cr\rhere lf\nhere 中文😀";

  /** binary COPY 与原样 text COPY 都必须精确保留每个标量，并按服务端确认行数对账。 */
  @Test
  void binaryCopiesExactScalarsAndReconcilesServerRowCount() throws SQLException {
    DataSource source = probeSource();
    DataSource target = probeTarget();
    insertValidRows(source);

    try (Connection sourceConnection = source.getConnection();
        Connection targetConnection = target.getConnection()) {
      targetConnection.setAutoCommit(false);
      long handled =
          LegacyTableCopy.binary(sourceConnection, targetConnection, PROBE_TABLE, PROBE_COLUMNS);
      assertEquals(2L, handled);
      assertEquals(2L, count(targetConnection, PROBE_TABLE));
      assertEquals(probeRows(sourceConnection), probeRows(targetConnection));
      assertExactScalars(targetConnection);
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));
    }
  }

  /** 逐行 text COPY（含真实转义）必须得到与 binary 完全相同的值。 */
  @Test
  void textCopiesEscapedScalarsAndReconcilesServerRowCount() throws SQLException {
    DataSource source = probeSource();
    DataSource target = probeTarget();
    insertValidRows(source);

    try (Connection sourceConnection = source.getConnection();
        Connection targetConnection = target.getConnection()) {
      targetConnection.setAutoCommit(false);
      long handled =
          LegacyTableCopy.text(
              sourceConnection,
              targetConnection,
              PROBE_TABLE,
              PROBE_COLUMNS,
              LegacyTableCopyPostgresIntegrationTest::identity);
      assertEquals(2L, handled);
      assertEquals(2L, count(targetConnection, PROBE_TABLE));
      assertEquals(probeRows(sourceConnection), probeRows(targetConnection));
      assertExactScalars(targetConnection);
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));
    }
  }

  /** child-first 的 self-FK 行以单条语句复制成功；源堆顺序先被断言为真正的 child-first。 */
  @Test
  void selfReferencingChildFirstCopySucceedsInOneStatement() throws SQLException {
    DataSource source = probeSource();
    DataSource target = probeTarget();
    insertSelfRows(source);
    assertEquals(List.of(SELF_CHILD, SELF_PARENT), selfOrderByPhysicalOrder(source));

    try (Connection sourceConnection = source.getConnection();
        Connection targetConnection = target.getConnection()) {
      targetConnection.setAutoCommit(false);
      long handled =
          LegacyTableCopy.binary(
              sourceConnection, targetConnection, SELF_TABLE, List.of("id", "parent_id"));
      assertEquals(2L, handled);
      assertEquals(
          1L,
          longValue(
              targetConnection,
              "select count(*) from "
                  + SELF_TABLE
                  + " where id = '"
                  + SELF_CHILD
                  + "' and parent_id = '"
                  + SELF_PARENT
                  + "'"));
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, SELF_TABLE));
    }
  }

  /** 真实即时外键拒绝：23503、目标零部分写入，随后两个连接都可回滚并继续复用。 */
  @Test
  void foreignKeyViolationIsRejectedWith23503AndConnectionsStayReusable() throws SQLException {
    DataSource source = probeSource();
    DataSource target = probeTarget();
    insertValidRows(source);
    insertRow(
        source, ROW_BAD_PARENT, MISSING_PARENT, "bad-parent", null, null, null, null, "bad parent");

    try (Connection sourceConnection = source.getConnection();
        Connection targetConnection = target.getConnection()) {
      targetConnection.setAutoCommit(false);
      SQLException failure =
          assertThrows(
              SQLException.class,
              () ->
                  LegacyTableCopy.binary(
                      sourceConnection, targetConnection, PROBE_TABLE, PROBE_COLUMNS));
      assertEquals("23503", failure.getSQLState());
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));

      deleteRow(source, ROW_BAD_PARENT);
      long handled =
          LegacyTableCopy.binary(sourceConnection, targetConnection, PROBE_TABLE, PROBE_COLUMNS);
      assertEquals(2L, handled);
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));
      // 源连接的 COPY OUT 也已被真实取消：同一连接可以继续执行普通查询。
      assertEquals(2L, longValue(sourceConnection, "select count(*) from " + PROBE_TABLE));
    }
  }

  /** 真实唯一索引拒绝：binary 与 text 两种运输都在同一连接上真实触发 23505，取消后连接仍可复用。 */
  @Test
  void uniqueViolationIsRejectedWith23505InBothCopyModesAndConnectionStaysReusable()
      throws SQLException {
    DataSource source = probeSource();
    DataSource target = probeTarget();
    insertValidRows(source);
    insertRow(source, ROW_DUPLICATE_ONE, null, "duplicate", null, null, null, null, null);
    insertRow(source, ROW_DUPLICATE_TWO, null, "duplicate", null, null, null, null, null);

    try (Connection sourceConnection = source.getConnection();
        Connection targetConnection = target.getConnection()) {
      targetConnection.setAutoCommit(false);
      SQLException binaryFailure =
          assertThrows(
              SQLException.class,
              () ->
                  LegacyTableCopy.binary(
                      sourceConnection, targetConnection, PROBE_TABLE, PROBE_COLUMNS));
      assertEquals("23505", binaryFailure.getSQLState());
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));

      SQLException textFailure =
          assertThrows(
              SQLException.class,
              () ->
                  LegacyTableCopy.text(
                      sourceConnection,
                      targetConnection,
                      PROBE_TABLE,
                      PROBE_COLUMNS,
                      LegacyTableCopyPostgresIntegrationTest::identity));
      assertEquals("23505", textFailure.getSQLState());
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));

      deleteRow(source, ROW_DUPLICATE_ONE);
      deleteRow(source, ROW_DUPLICATE_TWO);
      long handled =
          LegacyTableCopy.text(
              sourceConnection,
              targetConnection,
              PROBE_TABLE,
              PROBE_COLUMNS,
              LegacyTableCopyPostgresIntegrationTest::identity);
      assertEquals(2L, handled);
      targetConnection.rollback();
      assertEquals(0L, count(targetConnection, PROBE_TABLE));
    }
  }

  /** 精确标量断言：高精度 jsonb 数值不经 double，BYTEA / TEXT[] / TIMESTAMPTZ(3) 与转义字符串逐值一致。 */
  private static void assertExactScalars(Connection target) throws SQLException {
    String tail = " from " + PROBE_TABLE + " where id = '" + ROW_PARENTED + "'";
    String beyondDouble = text(target, "select json_payload ->> 'beyondDoubleInteger'" + tail);
    assertEquals("9007199254740993", beyondDouble);
    // 任何 double 往返都会把它塌成 2^53（...992），因此这个不等式就是「不经 double」的直接证据。
    assertNotEquals("9007199254740992", beyondDouble);
    String huge = text(target, "select json_payload ->> 'positiveExponent'" + tail);
    assertTrue(huge.length() >= 300, "1e309 must survive as its exact decimal expansion");
    assertNotEquals("0", text(target, "select json_payload ->> 'negativeExponent'" + tail));
    assertEquals(
        1L,
        longValue(
            target,
            "select count(*) from "
                + PROBE_TABLE
                + " where (json_payload ->> 'negativeExponent')::numeric <> 0"
                + " and json_payload ->> 'emptyText' = ''"
                + " and json_payload -> 'nullValue' = 'null'::jsonb"));
    assertEquals("0001ff10", text(target, "select encode(blob_payload, 'hex')" + tail));
    assertEquals(TAG_LIST, text(target, "select tags::text" + tail));
    assertEquals(OBSERVED_AT, text(target, "select observed_at::text" + tail));
    assertEquals(
        1L,
        longValue(
            target,
            "select count(*) from "
                + PROBE_TABLE
                + " where id = '"
                + ROW_PARENTED
                + "' and note is null"));
    assertEquals(
        TRICKY_NOTE,
        text(target, "select note from " + PROBE_TABLE + " where id = '" + ROW_ORPHAN + "'"));
  }

  /** 逐列原样读取：getString 对 jsonb / bytea / text[] / timestamptz 都是精确且确定的表示。 */
  private static List<String> identity(ResultSet row) throws SQLException {
    List<String> values = new ArrayList<>(PROBE_COLUMNS.size());
    for (int index = 1; index <= PROBE_COLUMNS.size(); index++) {
      values.add(row.getString(index));
    }
    return values;
  }

  private static List<List<String>> probeRows(Connection connection) throws SQLException {
    return readRows(
        connection,
        "select id::text, parent_id::text, unique_key, json_payload::text,"
            + " encode(blob_payload, 'hex'), tags::text, observed_at::text, note from "
            + PROBE_TABLE
            + " order by id");
  }

  private static List<String> selfOrderByPhysicalOrder(DataSource source) throws SQLException {
    List<String> order = new ArrayList<>();
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select id::text from " + SELF_TABLE + " order by ctid")) {
      while (rows.next()) {
        order.add(rows.getString(1));
      }
    }
    return order;
  }

  private static List<List<String>> readRows(Connection connection, String sql)
      throws SQLException {
    List<List<String>> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement()) {
      statement.execute("set time zone 'UTC'");
      try (ResultSet result = statement.executeQuery(sql)) {
        int columns = result.getMetaData().getColumnCount();
        while (result.next()) {
          List<String> row = new ArrayList<>(columns);
          for (int index = 1; index <= columns; index++) {
            row.add(result.getString(index));
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private static DataSource probeSource() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.pristineTargetDatabase();
    execute(source, "legacy-table-copy-probe-schema.sql");
    return source;
  }

  private static DataSource probeTarget() throws SQLException {
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    execute(target, "legacy-table-copy-probe-schema.sql");
    execute(target, "legacy-table-copy-probe-target.sql");
    return target;
  }

  private static void execute(DataSource dataSource, String resource) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESOURCE_ROOT + resource));
    }
  }

  private static void insertValidRows(DataSource source) throws SQLException {
    insertRow(
        source,
        ROW_PARENTED,
        PARENT_ID,
        "k1",
        JSON_PAYLOAD,
        BLOB_PAYLOAD,
        TAG_LIST,
        OBSERVED_AT,
        null);
    insertRow(source, ROW_ORPHAN, null, "k2", null, null, null, null, TRICKY_NOTE);
  }

  private static void insertSelfRows(DataSource source) throws SQLException {
    // 单条多行 INSERT 先写子行再写父行：即时外键在语句末校验，源库本身合法，堆顺序即 child-first。
    try (Connection connection = source.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into "
                    + SELF_TABLE
                    + " (id, parent_id) values (cast(? as uuid), cast(? as uuid)),"
                    + " (cast(? as uuid), cast(? as uuid))")) {
      statement.setString(1, SELF_CHILD);
      statement.setString(2, SELF_PARENT);
      statement.setString(3, SELF_PARENT);
      statement.setNull(4, Types.OTHER);
      statement.executeUpdate();
    }
  }

  private static void insertRow(
      DataSource source,
      String id,
      String parentId,
      String uniqueKey,
      String jsonPayload,
      byte[] blobPayload,
      String tags,
      String observedAt,
      String note)
      throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into "
                    + PROBE_TABLE
                    + " (id, parent_id, unique_key, json_payload, blob_payload, tags,"
                    + " observed_at, note) values (cast(? as uuid), cast(? as uuid), ?,"
                    + " cast(? as jsonb), cast(? as bytea), cast(? as text[]),"
                    + " cast(? as timestamptz), ?)")) {
      statement.setString(1, id);
      setNullable(statement, 2, parentId);
      setNullable(statement, 3, uniqueKey);
      setNullable(statement, 4, jsonPayload);
      if (blobPayload == null) {
        statement.setNull(5, Types.OTHER);
      } else {
        statement.setBytes(5, blobPayload);
      }
      setNullable(statement, 6, tags);
      setNullable(statement, 7, observedAt);
      setNullable(statement, 8, note);
      statement.executeUpdate();
    }
  }

  private static void deleteRow(DataSource source, String id) throws SQLException {
    try (Connection connection = source.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from " + PROBE_TABLE + " where id = cast(? as uuid)")) {
      statement.setString(1, id);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void setNullable(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setString(index, value);
    }
  }

  private static long count(Connection connection, String table) throws SQLException {
    return longValue(connection, "select count(*) from " + table);
  }

  private static long longValue(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static String text(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }
}
