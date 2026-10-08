package fun.fengwk.kkstudio.notification;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/**
 * The single application transaction manager for the notification DataSource: Spring Boot's default
 * {@link JdbcTransactionManager} semantics (commit/rollback SQL-exception translation) plus a
 * highest-precedence phase marker registered at the start of every physical transaction.
 *
 * <p>The marker lets {@link DefaultNotificationBus} reject a publish issued on the afterCommit
 * thread even when nothing was published earlier in the transaction; otherwise such a publish would
 * register a batch that can never commit and would be silently dropped.
 */
public class NotificationTransactionManager extends JdbcTransactionManager {
  public NotificationTransactionManager(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareSynchronization(
      DefaultTransactionStatus status, TransactionDefinition definition) {
    super.prepareSynchronization(status, definition);
    if (status.isNewSynchronization()) {
      TransactionSynchronizationManager.registerSynchronization(new PhaseSynchronization());
    }
  }

  static boolean isPostCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (sync instanceof PhaseSynchronization phase && phase.isCommitted()) {
        return true;
      }
    }
    return false;
  }

  static final class PhaseSynchronization implements TransactionSynchronization {
    private volatile boolean committed;

    boolean isCommitted() {
      return committed;
    }

    @Override
    public int getOrder() {
      return Integer.MIN_VALUE;
    }

    @Override
    public void afterCommit() {
      committed = true;
    }

    @Override
    public void afterCompletion(int status) {
      committed = true;
    }
  }
}
