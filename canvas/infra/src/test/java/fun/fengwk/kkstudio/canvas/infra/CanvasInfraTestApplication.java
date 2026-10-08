package fun.fengwk.kkstudio.canvas.infra;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.canvas.CanvasBlobReleaser;
import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.notification.NotificationLimits;

import javax.sql.DataSource;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
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

  @Bean(destroyMethod = "close")
  DefaultNotificationBus notificationBus(DataSource dataSource) {
    return new DefaultNotificationBus(
        dataSource,
        UUID.randomUUID(),
        List.of(CanvasNotifications.REVISION, CanvasNotifications.FUNCTION_WORK),
        NotificationLimits.defaults(),
        Duration.ofMillis(100),
        Duration.ofMillis(100));
  }

  /**
   * 宿主 Storage 的 Blob 引用释放端口。
   *
   * <p>生产实现由 Platform 注入；模块测试直接按 {@code storage_blob.ref_count} 语义释放，使「删除 Resource 行」与「释放 Blob
   * 引用」必须同事务完成的事实可被真实断言，而不是用 mock 掩盖。
   */
  @Bean
  CanvasBlobReleaser canvasBlobReleaser(JdbcTemplate jdbc) {
    return blobId -> {
      // 与 Storage releaseOnce 的单语句不变式一致：减到 0 的同一语句内切换到 DELETING。
      int updated =
          jdbc.update(
              "update storage_blob set ref_count = ref_count - 1,"
                  + " state = case when ref_count - 1 = 0 then 'DELETING' else state end"
                  + " where id = ? and state = 'ACTIVE' and ref_count >= 1",
              blobId);
      if (updated != 1) {
        throw new IllegalStateException("cannot release unreferenced storage blob: " + blobId);
      }
    };
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
