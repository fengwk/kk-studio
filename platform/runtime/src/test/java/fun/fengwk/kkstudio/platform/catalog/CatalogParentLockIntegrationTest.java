package fun.fengwk.kkstudio.platform.catalog;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.runtime.HarnessExecutionSelectionDTO;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 父行锁串行化子行创建与父行删除。 */
class CatalogParentLockIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ProviderService providerService;
  @Autowired private ModelService modelService;
  @Autowired private AgentService agentService;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ModelRepository modelRepository;
  @Autowired private AgentRepository agentRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void providerDeleteCannotCommitAfterModelCreationHoldingProviderLock() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = providerService.createProvider(provider("lock-provider-" + suffix));
    ModelCreateDTO modelCreate = model(provider.getName(), "lock-model-" + suffix);
    AtomicReference<ModelDTO> createdModel = new AtomicReference<>();
    CountDownLatch childCreated = new CountDownLatch(1);
    CountDownLatch releaseChildTransaction = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      Future<?> modelCreation =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        assertNotNull(providerRepository.getByNameForUpdate(provider.getName()));
                        createdModel.set(modelService.createModel(modelCreate));
                        childCreated.countDown();
                        await(releaseChildTransaction);
                      }));
      assertTrue(childCreated.await(5, TimeUnit.SECONDS));

      Future<?> providerDeletion =
          executor.submit(
              () -> {
                deleteStarted.countDown();
                assertThrows(
                    DomainException.class,
                    () ->
                        providerService.deleteProvider(provider.getName(), provider.getVersion()));
                return null;
              });
      assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
      releaseChildTransaction.countDown();

      modelCreation.get(5, TimeUnit.SECONDS);
      providerDeletion.get(5, TimeUnit.SECONDS);
      assertNotNull(modelRepository.getByName(modelCreate.getName()));
      assertNotNull(providerRepository.getByName(provider.getName()));
    } finally {
      releaseChildTransaction.countDown();
      executor.shutdownNow();
    }
    ModelDTO model = createdModel.get();
    modelService.deleteModel(model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void modelDeleteCannotCommitAfterAgentCreationHoldingModelLock() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider =
        providerService.createProvider(provider("lock-agent-provider-" + suffix));
    ModelDTO model =
        modelService.createModel(model(provider.getName(), "lock-agent-model-" + suffix));
    AgentCreateDTO agentCreate = agent(model.getName(), "lock-agent-" + suffix);
    AtomicReference<AgentDTO> createdAgent = new AtomicReference<>();
    CountDownLatch childCreated = new CountDownLatch(1);
    CountDownLatch releaseChildTransaction = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      Future<?> agentCreation =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        assertNotNull(modelRepository.getByNameForUpdate(model.getName()));
                        createdAgent.set(agentService.createAgent(agentCreate));
                        childCreated.countDown();
                        await(releaseChildTransaction);
                      }));
      assertTrue(childCreated.await(5, TimeUnit.SECONDS));

      Future<?> modelDeletion =
          executor.submit(
              () -> {
                deleteStarted.countDown();
                assertThrows(
                    DomainException.class,
                    () -> modelService.deleteModel(model.getName(), model.getVersion()));
                return null;
              });
      assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
      releaseChildTransaction.countDown();

      agentCreation.get(5, TimeUnit.SECONDS);
      modelDeletion.get(5, TimeUnit.SECONDS);
      assertNotNull(agentRepository.getByName(createdAgent.get().getName()));
      assertNotNull(modelRepository.getByName(model.getName()));
    } finally {
      releaseChildTransaction.countDown();
      executor.shutdownNow();
    }
    agentService.deleteAgent(createdAgent.get().getName(), createdAgent.get().getVersion());
    modelService.deleteModel(model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void crossReferencedAgentUpdatesUseOneCanonicalLockOrder() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider =
        providerService.createProvider(provider("cross-agent-provider-" + suffix));
    ModelDTO model =
        modelService.createModel(model(provider.getName(), "cross-agent-model-" + suffix));
    String modelRef = model.getName();
    AgentDTO createdAlpha = agentService.createAgent(agent(modelRef, "cross-alpha-" + suffix));
    AgentDTO createdOmega = agentService.createAgent(agent(modelRef, "cross-omega-" + suffix));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<AgentDTO> alphaUpdate =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return agentService.updateAgent(
                    createdAlpha.getName(), update(createdAlpha, List.of(createdOmega.getName())));
              });
      Future<AgentDTO> omegaUpdate =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return agentService.updateAgent(
                    createdOmega.getName(), update(createdOmega, List.of(createdAlpha.getName())));
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      AgentDTO alpha = alphaUpdate.get(5, TimeUnit.SECONDS);
      AgentDTO omega = omegaUpdate.get(5, TimeUnit.SECONDS);
      alpha = agentService.updateAgent(alpha.getName(), update(alpha, List.of()));
      omega = agentService.updateAgent(omega.getName(), update(omega, List.of()));
      agentService.deleteAgent(alpha.getName(), alpha.getVersion());
      agentService.deleteAgent(omega.getName(), omega.getVersion());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    modelService.deleteModel(model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  private ProviderCreateDTO provider(String name) {
    ProviderCreateDTO create = new ProviderCreateDTO();
    create.setName(name);
    create.setProviderType(ProviderType.OPENAI);
    return create;
  }

  private ModelCreateDTO model(String providerName, String name) {
    SingleModelCreateDTO create = new SingleModelCreateDTO();
    create.setProviderName(providerName);
    create.setName(name);
    executable(create);
    return create;
  }

  private AgentCreateDTO agent(String model, String name) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentCreateDTO create = new AgentCreateDTO();
    create.setName(name);
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver(BuiltinExecution.DRIVER);
    execution.setSettings(Map.of("modelName", model, "variant", "default"));
    create.setExecution(execution);
    create.setConfig(config);
    return create;
  }

  private AgentUpdateDTO update(AgentDTO current, List<String> subagentNames) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(current.getConfig().getTools());
    config.setSkills(current.getConfig().getSkills());
    config.setSubagents(subagentNames);
    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setDescription(current.getDescription());
    update.setSystemPrompt(current.getSystemPrompt());
    update.setExecution(current.getExecution());
    update.setConfig(config);
    update.setExpectedVersion(current.getVersion());
    return update;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for child transaction release", error);
    }
  }
}
