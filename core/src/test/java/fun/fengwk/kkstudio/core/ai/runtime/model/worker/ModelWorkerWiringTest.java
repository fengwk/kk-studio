package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorkerConfig;

/**
 * Verifies disabled test deployment composes Model worker infrastructure without consuming Redis
 * activation messages.
 */
class ModelWorkerWiringTest extends PostgresSpringTestSupport {

  // The unrelated tool slice owns the production {@link EnvironmentReadyListener} bean; this test
  // only verifies the Model worker wiring, so the listener dependency is satisfied with a mock.
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Autowired private ModelWorker modelWorker;
  @Autowired private ModelWorkerConfig modelWorkerConfig;

  @Test
  void composesModelWorkerInfrastructureWhileWorkersAreDisabled() {
    assertNotNull(modelWorker);
    assertNotNull(modelWorkerConfig);
  }
}
