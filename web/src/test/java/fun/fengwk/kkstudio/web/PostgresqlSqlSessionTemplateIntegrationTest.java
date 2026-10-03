package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.platform.environment.repo.impl.mapper.EnvironmentMapper;
import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;

import java.util.UUID;

class PostgresqlSqlSessionTemplateIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ConfigurableApplicationContext context;
  @Autowired private EnvironmentMapper mapper;
  @Autowired private SqlSessionTemplate template;

  @Test
  void autoConfiguredMapperUsesPostgresqlTemplateAgainstRealDatabase() {
    // 完整生产自动配置（含排序和扫描）只能注册一个模板，真实 Mapper 必须注入它并保留 PG 分类。
    assertEquals(1, context.getBeansOfType(SqlSessionTemplate.class).size());
    String mapperName = context.getBeanNamesForType(EnvironmentMapper.class)[0];
    MapperFactoryBean<?> mapperFactory = context.getBean("&" + mapperName, MapperFactoryBean.class);
    assertSame(template, mapperFactory.getSqlSession());
    assertEquals(
        "postgresqlPersistenceConfiguration",
        context.getBeanFactory().getBeanDefinition("sqlSessionTemplate").getFactoryBeanName());

    EnvironmentDO row = new EnvironmentDO();
    row.setId(UUID.randomUUID());
    row.setName("sql-session-template-test");
    row.setRegistrationToken("disposable-template-test-token");
    assertEquals(1, mapper.insert(row));
    assertNotNull(mapper.getById(row.getId()));
    assertThrows(DuplicateKeyException.class, () -> mapper.insert(row));
  }
}
