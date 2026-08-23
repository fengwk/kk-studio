package fun.fengwk.kkstudio.platform.storage.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * {@code uuid} 列与 {@link UUID} 的双向类型处理器。
 *
 * <p>当前 MyBatis 版本没有内置 UUID 处理器，而 storage 表的全部主键/外键都是 uuid； 该处理器随 {@link
 * StoragePersistenceConfiguration} 注册到共享 MyBatis 配置，映射与参数解析共用。
 *
 * @author fengwk
 */
public class StorageUuidTypeHandler extends BaseTypeHandler<UUID> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, parameter);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return rs.getObject(columnName, UUID.class);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return rs.getObject(columnIndex, UUID.class);
  }

  @Override
  public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return cs.getObject(columnIndex, UUID.class);
  }
}
