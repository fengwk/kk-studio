package fun.fengwk.kkstudio.platform.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.UUID;

/** Serializes a product's user-wide invariants before any space row is locked. */
@Component
public class StudioUserLock {
  private final JdbcClient jdbc;

  public StudioUserLock(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void acquire(UUID userId) {
    jdbc.sql("select id from studio_user where id = :id for update")
        .param("id", userId)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> DomainException.notFound("user"));
  }
}
