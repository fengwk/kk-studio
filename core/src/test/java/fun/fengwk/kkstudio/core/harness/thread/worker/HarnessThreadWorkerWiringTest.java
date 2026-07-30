package fun.fengwk.kkstudio.core.harness.thread.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;

/** Worker-disabled contexts still compose the final Thread reconciler. */
class HarnessThreadWorkerWiringTest extends PostgresSpringTestSupport {

  @Autowired private ThreadReconciler reconciler;

  @Test
  void composesFinalThreadWorkerWhileWorkersAreDisabled() {
    assertNotNull(reconciler);
  }
}
