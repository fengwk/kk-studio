package fun.fengwk.kkstudio.project.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Issue 邮箱的领取、执行与租约归还；线程池及调度生命周期由组合根持有。 */
@Slf4j
@Component
public class IssueReconcileWorkHandler {

  private final DurableWorkStore workQueue;
  private final IssueReconciler reconciler;
  private final IssueReconcileProperties properties;
  private final TransactionTemplate transactions;

  public IssueReconcileWorkHandler(
      @Qualifier("issueWorkQueue") DurableWorkStore workQueue,
      IssueReconciler reconciler,
      IssueReconcileProperties properties,
      PlatformTransactionManager transactionManager) {
    this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  public WorkClaim claimNext() {
    return workQueue
        .claimNext(UUID.randomUUID().toString(), properties.getLeaseDuration())
        .orElse(null);
  }

  public void runHandoff(WorkClaim claim) {
    try {
      reconciler.reconcile(claim);
    } catch (RuntimeException error) {
      log.error(
          "Reconciler failed for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
      try {
        rescheduleOwned(claim, properties.getRetryDelay());
      } catch (RuntimeException rescheduleError) {
        log.warn(
            "Failed to reschedule work after reconciler failure for issueId={}; failure={}",
            claim.targetId(),
            rescheduleError.getClass().getName());
      }
    }
  }

  public void returnClaim(WorkClaim claim) {
    try {
      rescheduleOwned(claim, properties.getRejectionDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to return claim for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
    }
  }

  private void rescheduleOwned(WorkClaim claim, Duration delay) {
    transactions.executeWithoutResult(
        status -> {
          if (workQueue.lockClaimed(claim).isPresent()) {
            workQueue.reschedule(claim, delay);
          }
        });
  }
}
