package fun.fengwk.kkstudio.platform.persistence;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

/**
 * 本仓唯一 durable 数据库是 PostgreSQL。异常翻译固定产品名，不在故障路径上读取数据库元数据， 同时保留 Spring 的 PostgreSQL SQLState 分类与
 * MyBatis 的事务/session 生命周期。
 */
@Configuration(proxyBeanMethods = false)
public class PostgresqlPersistenceConfiguration {

  @Bean
  public SqlSessionTemplate sqlSessionTemplate(
      SqlSessionFactory factory, MybatisProperties properties) {
    ExecutorType executorType = properties.getExecutorType();
    if (executorType == null) {
      executorType = factory.getConfiguration().getDefaultExecutorType();
    }
    return new SqlSessionTemplate(
        factory,
        executorType,
        new MyBatisExceptionTranslator(
            () -> new SQLErrorCodeSQLExceptionTranslator("PostgreSQL"), false));
  }
}
