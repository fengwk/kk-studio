package fun.fengwk.kkstudio.web.upgrade;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.CopyOut;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 共有表在两个 PostgreSQL 连接之间的原生 COPY 运输：整表单语句、不经过 JDBC getObject、不整表缓存。
 *
 * <ul>
 *   <li>{@link #binary}：binary COPY OUT → COPY IN 直接流式对接，保留原生类型字节，并且每张含 self-FK
 *       的表仍是单语句，因此语句末校验时父子行都已就位；
 *   <li>{@link #text}：需要逐行标量转换的两张表用 text COPY，源端按 {@code fetchSize} 流式读取、逐行转换后按 COPY text 规则转义（反斜线
 *       / 制表符 / 回车 / 换行 / NULL），每表仍只有一个 COPY IN 语句。
 * </ul>
 *
 * <p>返回服务端确认的 handled row count（未知为 -1），供调用方与目标 COUNT 一起对账。失败时仍尝试取消全部已打开的 COPY，取消失败挂回原失败：取消 SQL
 * 失败既可能是服务端已结束，也可能是网络/协议失败，一律不当作正常结束。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的运输层，不是平台 API。
 */
final class LegacyTableCopy {

  /** 源表默认流式读取批量；源连接是只读一致快照，不把整表读进内存。 */
  static final int FETCH_SIZE = 512;

  private LegacyTableCopy() {}

  /** 逐行把结果集投影为按目标列顺序排列的原始文本字段；返回 {@code null} 元素表示 SQL NULL。 */
  @FunctionalInterface
  interface RowTransformer {
    List<String> transform(ResultSet row) throws SQLException;
  }

  /** 整表 binary COPY；返回服务端确认写入的行数。 */
  static long binary(Connection source, Connection target, String table, List<String> columns)
      throws SQLException {
    String columnList = String.join(", ", columns);
    CopyManager sourceCopy = source.unwrap(PGConnection.class).getCopyAPI();
    CopyManager targetCopy = target.unwrap(PGConnection.class).getCopyAPI();
    CopyOut copyOut =
        sourceCopy.copyOut(
            "COPY (SELECT " + columnList + " FROM " + table + ") TO STDOUT (FORMAT binary)");
    CopyIn copyIn = null;
    try {
      copyIn =
          targetCopy.copyIn("COPY " + table + " (" + columnList + ") FROM STDIN (FORMAT binary)");
      byte[] buffer;
      while ((buffer = copyOut.readFromCopy()) != null) {
        copyIn.writeToCopy(buffer, 0, buffer.length);
      }
      copyIn.endCopy();
      return copyIn.getHandledRowCount();
    } catch (Throwable failure) {
      cancelAll(failure, copyIn, copyOut);
      throw failure;
    }
  }

  /** 逐行转换的 text COPY；返回服务端确认写入的行数。 */
  static long text(
      Connection source,
      Connection target,
      String table,
      List<String> columns,
      RowTransformer transformer)
      throws SQLException {
    String columnList = String.join(", ", columns);
    CopyManager targetCopy = target.unwrap(PGConnection.class).getCopyAPI();
    CopyIn copyIn = targetCopy.copyIn("COPY " + table + " (" + columnList + ") FROM STDIN");
    try {
      try (Statement select = source.createStatement()) {
        select.setFetchSize(FETCH_SIZE);
        try (ResultSet rows = select.executeQuery("SELECT " + columnList + " FROM " + table)) {
          StringBuilder line = new StringBuilder(256);
          while (rows.next()) {
            List<String> values = transformer.transform(rows);
            line.setLength(0);
            for (int index = 0; index < values.size(); index++) {
              if (index > 0) {
                line.append('\t');
              }
              appendField(line, values.get(index));
            }
            line.append('\n');
            byte[] encoded = line.toString().getBytes(StandardCharsets.UTF_8);
            copyIn.writeToCopy(encoded, 0, encoded.length);
          }
        }
      }
      copyIn.endCopy();
      return copyIn.getHandledRowCount();
    } catch (Throwable failure) {
      cancelAll(failure, copyIn, null);
      throw failure;
    }
  }

  /** COPY text 的单字段转义：NULL 为未转义的 {@code \N}，字面量反斜线/制表符/回车/换行全部转义。 */
  private static void appendField(StringBuilder line, String value) {
    if (value == null) {
      line.append("\\N");
      return;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> line.append("\\\\");
        case '\t' -> line.append("\\t");
        case '\n' -> line.append("\\n");
        case '\r' -> line.append("\\r");
        default -> line.append(character);
      }
    }
  }

  /**
   * 取消全部已打开的 COPY：任一取消失败都不跳过其它 COPY；取消失败挂在原失败上，取消产生的 Error 在无 Error 原失败时向外传播（原失败保留为 suppressed）。
   */
  private static void cancelAll(Throwable failure, CopyIn copyIn, CopyOut copyOut) {
    Error firstCancelError = cancel(failure, copyIn);
    Error secondCancelError = cancel(failure, copyOut);
    if (firstCancelError == null) {
      firstCancelError = secondCancelError;
    }
    if (firstCancelError != null && !(failure instanceof Error)) {
      if (firstCancelError != failure) {
        firstCancelError.addSuppressed(failure);
      }
      throw firstCancelError;
    }
  }

  /** 取消一个已打开的 COPY；返回取消过程中的 Error（SQL 失败挂回原失败），无 Error 时为 null。 */
  private static Error cancel(Throwable failure, CopyIn copyIn) {
    if (copyIn == null) {
      return null;
    }
    try {
      copyIn.cancelCopy();
    } catch (SQLException cancelFailure) {
      if (cancelFailure != failure) {
        failure.addSuppressed(cancelFailure);
      }
    } catch (Error cancelFailure) {
      return cancelFailure;
    }
    return null;
  }

  private static Error cancel(Throwable failure, CopyOut copyOut) {
    if (copyOut == null) {
      return null;
    }
    try {
      copyOut.cancelCopy();
    } catch (SQLException cancelFailure) {
      if (cancelFailure != failure) {
        failure.addSuppressed(cancelFailure);
      }
    } catch (Error cancelFailure) {
      return cancelFailure;
    }
    return null;
  }
}
