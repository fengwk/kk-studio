package fun.fengwk.kkstudio.core.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.worker.AgentTurnWorkerLifecycle;
import fun.fengwk.kkstudio.core.harness.tool.worker.CloudToolWorkerLifecycle;
import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.run.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryDraft;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;

import java.util.List;
import java.util.Set;

@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRuntimeWiringIntegrationTest {

  @Autowired private SessionTree sessionTree;
  @Autowired private TurnResourceResolver resourceResolver;
  @Autowired private CompactionService compactionService;
  @Autowired private AgentTurnWorker agentTurnWorker;
  @Autowired private CloudToolWorker cloudToolWorker;
  @Autowired private AgentTurnWorkerLifecycle turnLifecycle;
  @Autowired private CloudToolWorkerLifecycle toolLifecycle;

  /**
   * H2 seed resolves through the real bean graph while the safe compactor never invents a summary.
   */
  @Test
  void wiresExecutableSeedResourcesWorkersAndSafeCompaction() {
    AgentSnapshot snapshot =
        new AgentSnapshot("system", "1", "default", List.of(), List.of(), List.of(), "{}");
    Session session = sessionTree.create(1L, "runtime-wiring");
    assertNotNull(
        sessionTree
            .append(
                session.id(),
                null,
                new SessionEntryDraft(null, new AgentSnapshotEntryPayload(snapshot)))
            .entry());

    var resources = resourceResolver.resolve(session.id(), AgentRuntimeConfig.from(snapshot));

    assertEquals(1L, resources.model().providerResourceId());
    assertEquals(1L, resources.model().modelResourceId());
    assertEquals("acceptance-stub", resources.model().modelId());
    assertEquals("default", resources.variant().name());
    assertEquals(32768L, resources.model().contextWindow());
    assertEquals(4096L, resources.model().maxOutputTokens());
    assertEquals(
        Set.of(ModelCapability.TEXT, ModelCapability.TOOLS), resources.model().capabilities());
    assertFalse(
        compactionService
            .compact(session.id(), new SessionContext(AgentRuntimeConfig.from(snapshot), List.of()))
            .isPresent());
    assertNotNull(agentTurnWorker);
    assertNotNull(cloudToolWorker);
    assertFalse(turnLifecycle.isAutoStartup());
    assertFalse(toolLifecycle.isAutoStartup());
  }
}
