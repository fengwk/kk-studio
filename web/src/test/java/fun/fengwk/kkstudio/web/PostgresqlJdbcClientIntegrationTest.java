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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;

import java.util.UUID;

class PostgresqlJdbcClientIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ConfigurableApplicationContext context;
  @Autowired private EnvironmentRepository repository;
  @Autowired private JdbcClient client;
  @Autowired private JdbcTemplate template;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void autoConfiguredJdbcClientAndRepositoryPreservePostgresqlClassification() {
    // 完整宿主装配只注册一个 JdbcClient，并将 PostgreSQL 翻译器用于真实数据库操作。
    assertEquals(1, context.getBeansOfType(JdbcClient.class).size());
    assertSame(context.getBean(SQLExceptionTranslator.class), template.getExceptionTranslator());

    Environment row = new Environment();
    row.setId(UUID.randomUUID());
    row.setName("jdbc-client-test");
    row.setRegistrationTokenHash("0".repeat(64));
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertEquals(Boolean.TRUE, transaction.execute(status -> repository.create(row)));
    assertNotNull(repository.getById(row.getId()));
    assertEquals(
        row.getName(),
        client
            .sql("select name from environment where id = :id")
            .param("id", row.getId())
            .query(String.class)
            .single());
    assertThrows(
        DuplicateKeyException.class, () -> transaction.execute(status -> repository.create(row)));
  }
}
