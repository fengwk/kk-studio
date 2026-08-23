package fun.fengwk.kkstudio.canvas.infra;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Canvas Infra PostgreSQL 集成测试的最小 Spring Boot 组合根。 */
@SpringBootApplication
public class CanvasInfraTestApplication {

  /** 生产组合根会注册共享 UUID handler；模块测试在不依赖 Platform 的前提下提供相同 JDBC 映射，使 Mapper 直接连接 PostgreSQL。 */
  @Bean
  ConfigurationCustomizer uuidTypeHandlerRegistration() {
    return configuration ->
        configuration.getTypeHandlerRegistry().register(UUID.class, new TestUuidTypeHandler());
  }

  private static final class TestUuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
        PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)
        throws SQLException {
      statement.setObject(index, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
      return resultSet.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
      return resultSet.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex)
        throws SQLException {
      return statement.getObject(columnIndex, UUID.class);
    }
  }
}
