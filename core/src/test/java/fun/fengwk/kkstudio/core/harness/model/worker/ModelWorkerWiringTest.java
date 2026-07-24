package fun.fengwk.kkstudio.core.harness.model.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorkerConfig;

/**
 * Verifies disabled test deployment composes Model worker infrastructure without invoking adapters.
 */
class ModelWorkerWiringTest extends PostgresSpringTestSupport {

  @Autowired private ModelWorker modelWorker;
  @Autowired private ModelWorkerConfig modelWorkerConfig;
  @Autowired private ModelWorkerLifecycle modelWorkerLifecycle;

  @Test
  void composesModelWorkerInfrastructureWhileWorkersAreDisabled() {
    assertNotNull(modelWorker);
    assertNotNull(modelWorkerConfig);
    assertNotNull(modelWorkerLifecycle);
    assertFalse(modelWorkerLifecycle.isRunning());
  }
}
