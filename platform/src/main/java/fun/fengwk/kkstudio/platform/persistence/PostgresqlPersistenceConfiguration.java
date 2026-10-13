package fun.fengwk.kkstudio.platform.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * 本仓唯一 durable 数据库是 PostgreSQL。异常翻译固定产品名，不在故障路径上读取数据库元数据，保留 Spring 对 PostgreSQL SQLState 的分类；Boot
 * 自动配置的 {@code JdbcTemplate}/{@code NamedParameterJdbcTemplate}/{@code JdbcClient} 会采用这个翻译器。
 */
@Configuration(proxyBeanMethods = false)
public class PostgresqlPersistenceConfiguration {

  @Bean
  public SQLExceptionTranslator postgresqlSqlExceptionTranslator() {
    return new SQLErrorCodeSQLExceptionTranslator("PostgreSQL");
  }
}
