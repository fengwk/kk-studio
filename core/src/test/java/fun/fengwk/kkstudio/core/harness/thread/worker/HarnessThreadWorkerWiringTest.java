package fun.fengwk.kkstudio.core.harness.thread.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadActivationDispatcher;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;

/** 生产 composition root 只暴露 final Reconciler/dispatcher/recovery 资源。 */
class HarnessThreadWorkerWiringTest extends PostgresSpringTestSupport {

  @Autowired private ApplicationContext context;
  @Autowired private ThreadReconciler reconciler;
  @Autowired private ThreadKick threadKick;
  @Autowired private ThreadRecoveryLifecycle recoveryLifecycle;

  @Test
  void composesFinalThreadWorkerWhileWorkersAreDisabled() {
    assertNotNull(reconciler);
    assertNotNull(context.getBean(ThreadKick.class));
    assertInstanceOf(ThreadActivationDispatcher.class, threadKick);
    assertNotNull(recoveryLifecycle);
    assertFalse(recoveryLifecycle.isRunning());
  }
}
