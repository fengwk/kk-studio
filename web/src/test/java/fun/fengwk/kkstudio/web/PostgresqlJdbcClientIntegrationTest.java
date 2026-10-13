package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.environment.repo.impl.store.EnvironmentJdbcStore;

import java.util.UUID;

class PostgresqlJdbcClientIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ConfigurableApplicationContext context;
  @Autowired private EnvironmentJdbcStore store;
  @Autowired private JdbcClient client;
  @Autowired private JdbcTemplate template;

  @Test
  void autoConfiguredJdbcClientAndStorePreservePostgresqlClassification() {
    // 完整宿主装配只注册一个 JdbcClient，并将 PostgreSQL 翻译器用于真实数据库操作。
    assertEquals(1, context.getBeansOfType(JdbcClient.class).size());
    assertSame(context.getBean(SQLExceptionTranslator.class), template.getExceptionTranslator());

    EnvironmentDO row = new EnvironmentDO();
    row.setId(UUID.randomUUID());
    row.setName("jdbc-client-test");
    row.setRegistrationToken("disposable-jdbc-test-token");
    row.setInstallConfigJson("{}");
    assertEquals(1, store.insert(row));
    assertNotNull(store.getById(row.getId()));
    assertEquals(
        row.getName(),
        client
            .sql("select name from environment where id = :id")
            .param("id", row.getId())
            .query(String.class)
            .single());
    assertThrows(DuplicateKeyException.class, () -> store.insert(row));
  }
}
