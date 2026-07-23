package fun.fengwk.kkstudio.core.harness.model.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorkerConfig;

/**
 * Verifies disabled test deployment composes Model worker infrastructure without invoking adapters.
 */
@SpringBootTest(classes = CoreTestApplication.class)
class ModelWorkerWiringTest {

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
