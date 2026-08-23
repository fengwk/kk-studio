package fun.fengwk.kkstudio.platform.ai.catalog;

import static fun.fengwk.kkstudio.platform.ai.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.ai.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.ai.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.ai.error.AiInUseException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 父行锁串行化子行创建与父行删除。 */
class CatalogParentLockIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService providerService;
  @Autowired private AgentModelService modelService;
  @Autowired private AgentDefinitionService definitionService;
  @Autowired private AgentProviderRepository providerRepository;
  @Autowired private AgentModelRepository modelRepository;
  @Autowired private AgentDefinitionRepository definitionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void providerDeleteCannotCommitAfterModelCreationHoldingProviderLock() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider = providerService.createProvider(provider("lock-provider-" + suffix));
    AgentModelCreateDTO modelCreate = model(provider.getName(), "lock-model-" + suffix);
    AtomicReference<AgentModelDTO> createdModel = new AtomicReference<>();
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
                    AiInUseException.class,
                    () ->
                        providerService.deleteProvider(provider.getName(), provider.getVersion()));
                return null;
              });
      assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
      releaseChildTransaction.countDown();

      modelCreation.get(5, TimeUnit.SECONDS);
      providerDeletion.get(5, TimeUnit.SECONDS);
      assertNotNull(
          modelRepository.getByProviderNameAndName(provider.getName(), modelCreate.getName()));
      assertNotNull(providerRepository.getByName(provider.getName()));
    } finally {
      releaseChildTransaction.countDown();
      executor.shutdownNow();
    }
    AgentModelDTO model = createdModel.get();
    modelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void modelDeleteCannotCommitAfterAgentCreationHoldingModelLock() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider =
        providerService.createProvider(provider("lock-agent-provider-" + suffix));
    AgentModelDTO model =
        modelService.createModel(model(provider.getName(), "lock-agent-model-" + suffix));
    AgentDefinitionCreateDTO agentCreate =
        agent(provider.getName() + "/" + model.getName(), "lock-agent-" + suffix);
    AtomicReference<AgentDefinitionDTO> createdAgent = new AtomicReference<>();
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
                        assertNotNull(
                            modelRepository.getByProviderNameAndNameForUpdate(
                                provider.getName(), model.getName()));
                        createdAgent.set(definitionService.createAgent(agentCreate));
                        childCreated.countDown();
                        await(releaseChildTransaction);
                      }));
      assertTrue(childCreated.await(5, TimeUnit.SECONDS));

      Future<?> modelDeletion =
          executor.submit(
              () -> {
                deleteStarted.countDown();
                assertThrows(
                    AiInUseException.class,
                    () ->
                        modelService.deleteModel(
                            provider.getName(), model.getName(), model.getVersion()));
                return null;
              });
      assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
      releaseChildTransaction.countDown();

      agentCreation.get(5, TimeUnit.SECONDS);
      modelDeletion.get(5, TimeUnit.SECONDS);
      assertNotNull(definitionRepository.getByName(createdAgent.get().getName()));
      assertNotNull(modelRepository.getByProviderNameAndName(provider.getName(), model.getName()));
    } finally {
      releaseChildTransaction.countDown();
      executor.shutdownNow();
    }
    definitionService.deleteAgent(createdAgent.get().getName(), createdAgent.get().getVersion());
    modelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  @Test
  void crossReferencedAgentUpdatesUseOneCanonicalLockOrder() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    AgentProviderDTO provider =
        providerService.createProvider(provider("cross-agent-provider-" + suffix));
    AgentModelDTO model =
        modelService.createModel(model(provider.getName(), "cross-agent-model-" + suffix));
    String modelRef = provider.getName() + "/" + model.getName();
    AgentDefinitionDTO createdAlpha =
        definitionService.createAgent(agent(modelRef, "cross-alpha-" + suffix));
    AgentDefinitionDTO createdOmega =
        definitionService.createAgent(agent(modelRef, "cross-omega-" + suffix));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<AgentDefinitionDTO> alphaUpdate =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return definitionService.updateAgent(
                    createdAlpha.getName(), update(createdAlpha, List.of(createdOmega.getName())));
              });
      Future<AgentDefinitionDTO> omegaUpdate =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return definitionService.updateAgent(
                    createdOmega.getName(), update(createdOmega, List.of(createdAlpha.getName())));
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      AgentDefinitionDTO alpha = alphaUpdate.get(5, TimeUnit.SECONDS);
      AgentDefinitionDTO omega = omegaUpdate.get(5, TimeUnit.SECONDS);
      alpha = definitionService.updateAgent(alpha.getName(), update(alpha, List.of()));
      omega = definitionService.updateAgent(omega.getName(), update(omega, List.of()));
      definitionService.deleteAgent(alpha.getName(), alpha.getVersion());
      definitionService.deleteAgent(omega.getName(), omega.getVersion());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    modelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
    providerService.deleteProvider(provider.getName(), provider.getVersion());
  }

  private AgentProviderCreateDTO provider(String name) {
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName(name);
    create.setProviderType("openai");
    return create;
  }

  private AgentModelCreateDTO model(String providerName, String name) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setProviderName(providerName);
    create.setName(name);
    executable(create);
    return create;
  }

  private AgentDefinitionCreateDTO agent(String model, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(name);
    create.setModel(model);
    create.setVariant("default");
    create.setConfig(config);
    return create;
  }

  private AgentDefinitionUpdateDTO update(AgentDefinitionDTO current, List<String> subagentNames) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(current.getConfig().getTools());
    config.setSkills(current.getConfig().getSkills());
    config.setSubagents(subagentNames);
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription(current.getDescription());
    update.setSystemPrompt(current.getSystemPrompt());
    update.setModel(current.getModel());
    update.setVariant(current.getVariant());
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
