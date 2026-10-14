package fun.fengwk.kkstudio.platform.catalog;

import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Agent 引用与 Skill/MCP 生命周期必须共享同一把行锁。
 *
 * <p>测试意图：两个真实 PostgreSQL 事务在对方持锁点交错。Agent 写入先取 Skill/MCP {@code FOR SHARE}，删除与 discover 保持 {@code
 * FOR UPDATE}；无论谁先到达，提交后都不能留下悬空引用，交叉更新同一 Agent 也不能因锁序反转死锁。
 */
class CatalogReferenceLockIntegrationTest extends PostgresSpringTestSupport {

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @Autowired private AgentDefinitionService definitionService;
  @Autowired private AgentDefinitionRepository definitionRepository;
  @Autowired private AgentProviderService providerService;
  @Autowired private AgentModelService modelService;
  @Autowired private SkillPackageRepository skillPackages;
  @Autowired private McpServerService mcpServerService;
  @Autowired private McpServerRepository mcpServers;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createAgentCannotLeaveDanglingSkillAfterPackageDelete() throws Exception {
    String suffix = suffix();
    Model model = model("skill-create-" + suffix);
    String packageName = "pkg" + suffix;
    insertPackage(packageName, "dev");
    AgentDefinitionCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setSkills(List.of(skill(packageName, "dev")));

    Outcome<AgentDefinitionDTO> created =
        race(
            () -> definitionService.createAgent(create),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              skillServiceDelete(packageName);
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(create.getName());
    if (created.value() != null) {
      assertNotNull(persisted);
      assertNotNull(skillPackages.getPackage(packageName));
      definitionService.deleteAgent(created.value().getName(), created.value().getVersion());
    } else {
      assertTrue(created.error() instanceof AiValidationException);
      if (persisted != null) {
        definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
      }
    }
    deletePackage(packageName, skillPackages.getPackage(packageName) == null ? -1 : 0);
    model.delete();
  }

  @Test
  void updateAgentCannotReferenceSkillRemovedFromManifest() throws Exception {
    String suffix = suffix();
    Model model = model("skill-update-" + suffix);
    String packageName = "pkg" + suffix;
    insertPackage(packageName, "dev", "review");
    AgentDefinitionDTO created =
        definitionService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentDefinitionUpdateDTO update = update(created);
    update.getConfig().setSkills(List.of(skill(packageName, "review")));

    Outcome<AgentDefinitionDTO> updated =
        race(
            () -> definitionService.updateAgent(created.getName(), update),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              replaceManifest(packageName, "dev");
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(created.getName());
    boolean referencesReview = persisted.getConfigJson().contains("\"name\": \"review\"");
    if (referencesReview) {
      assertNotNull(skillPackages.getPackage(packageName).findSkill("review"));
    }
    assertTrue(updated.value() != null || updated.error() instanceof AiValidationException);
    definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    if (skillPackages.getPackage(packageName) != null) {
      deletePackage(packageName, skillPackages.getPackage(packageName).getVersion());
    }
    model.delete();
  }

  @Test
  void createAgentCannotLeaveSecondSkillDanglingAfterPartialManifestRemoval() throws Exception {
    String suffix = suffix();
    Model model = model("skill-second-" + suffix);
    String packageName = "pkg" + suffix;
    insertPackage(packageName, "alpha", "zeta");
    AgentDefinitionCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setSkills(List.of(skill(packageName, "alpha"), skill(packageName, "zeta")));

    Outcome<AgentDefinitionDTO> created =
        race(
            () -> definitionService.createAgent(create),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              replaceManifest(packageName, "alpha");
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(create.getName());
    if (persisted != null && persisted.getConfigJson().contains("\"name\": \"zeta\"")) {
      assertNotNull(skillPackages.getPackage(packageName).findSkill("zeta"));
    }
    assertTrue(created.value() != null || created.error() instanceof AiValidationException);
    if (persisted != null) {
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    }
    if (skillPackages.getPackage(packageName) != null) {
      deletePackage(packageName, skillPackages.getPackage(packageName).getVersion());
    }
    model.delete();
  }

  @Test
  void createAgentCannotLeaveDanglingMcpToolAfterDiscoverDeletesIt() throws Exception {
    String suffix = suffix();
    Model model = model("mcp-create-" + suffix);
    McpServerDTO server = mcp("mcp" + suffix.substring(Math.max(0, suffix.length() - 8)));
    insertTool(server.getName(), "critical");
    String toolName = "mcp_" + server.getName() + "_critical";
    AgentDefinitionCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(toolName));

    Outcome<AgentDefinitionDTO> created =
        race(
            () -> definitionService.createAgent(create),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              mcpServers.deleteTools(server.getName());
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(create.getName());
    if (persisted != null && persisted.getConfigJson().contains(toolName)) {
      assertTrue(
          mcpServers.getTool(toolName).isPresent(),
          () -> created.value() + " / " + created.error() + " / " + persisted.getConfigJson());
    }
    if (persisted != null) {
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    }
    assertTrue(
        created.value() != null
            || created.error() instanceof AiValidationException
            || persisted == null);
    mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    model.delete();
  }

  @Test
  void createAgentCannotLeaveNonMinimalToolDangling() throws Exception {
    String suffix = suffix();
    Model model = model("mcp-second-" + suffix);
    McpServerDTO server = mcp("two" + suffix.substring(Math.max(0, suffix.length() - 8)));
    insertTool(server.getName(), "alpha");
    insertTool(server.getName(), "zeta");
    String alpha = "mcp_" + server.getName() + "_alpha";
    String zeta = "mcp_" + server.getName() + "_zeta";
    AgentDefinitionCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(alpha, zeta));

    Outcome<AgentDefinitionDTO> created =
        race(
            () -> definitionService.createAgent(create),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              jdbc.update("delete from mcp_tool where name = ?", zeta);
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(create.getName());
    if (persisted != null && persisted.getConfigJson().contains(zeta)) {
      assertTrue(mcpServers.getTool(zeta).isPresent(), () -> persisted.getConfigJson());
    }
    assertTrue(
        created.value() != null
            || created.error() instanceof AiValidationException
            || persisted == null);
    if (persisted != null) {
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    }
    mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    model.delete();
  }

  @Test
  void createAgentCannotCommitWhenFindToolDisappearsBeforeReread() throws Exception {
    String suffix = suffix();
    Model model = model("mcp-gone-" + suffix);
    McpServerDTO server = mcp("gone" + suffix.substring(Math.max(0, suffix.length() - 8)));
    insertTool(server.getName(), "critical");
    String toolName = "mcp_" + server.getName() + "_critical";
    AgentDefinitionCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(toolName));

    race(
        () -> definitionService.createAgent(create),
        () -> {
          mcpServers.getForUpdate(server.getName());
          return null;
        },
        () -> {
          jdbc.update("delete from mcp_tool where name = ?", toolName);
          return null;
        });

    AgentDefinition persisted = definitionRepository.getByName(create.getName());
    if (persisted != null && persisted.getConfigJson().contains(toolName)) {
      assertTrue(mcpServers.getTool(toolName).isPresent());
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    }
    mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    model.delete();
  }

  @Test
  void updateAgentCannotReferenceServerDeletedConcurrently() throws Exception {
    String suffix = suffix();
    Model model = model("mcp-update-" + suffix);
    McpServerDTO server = mcp("del" + suffix.substring(Math.max(0, suffix.length() - 8)));
    insertTool(server.getName(), "critical");
    String toolName = "mcp_" + server.getName() + "_critical";
    AgentDefinitionDTO created =
        definitionService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentDefinitionUpdateDTO update = update(created);
    update.getConfig().setTools(List.of(toolName));

    Outcome<AgentDefinitionDTO> updated =
        race(
            () -> definitionService.updateAgent(created.getName(), update),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              try {
                mcpServerService.deleteServer(server.getName(), server.getVersion());
              } catch (AiInUseException ignored) {
                // Agent 已经在共享锁内写入引用：删除必须失败并保留 server。
              }
              return null;
            });

    AgentDefinition persisted = definitionRepository.getByName(created.getName());
    if (persisted.getConfigJson().contains(toolName)) {
      assertTrue(mcpServers.getByName(server.getName()).isPresent());
      assertTrue(mcpServers.getTool(toolName).isPresent());
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
      mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    } else {
      assertTrue(updated.error() instanceof AiValidationException || updated.value() != null);
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
      if (mcpServers.getByName(server.getName()).isPresent()) {
        mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
      }
    }
    model.delete();
  }

  @Test
  void concurrentUpdatesOfTheSameAgentDoNotDeadlock() throws Exception {
    String suffix = suffix();
    Model model = model("same-agent-" + suffix);
    String packageName = "pkg" + suffix;
    insertPackage(packageName, "dev");
    McpServerDTO server = mcp("same" + suffix.substring(Math.max(0, suffix.length() - 8)));
    insertTool(server.getName(), "critical");
    String toolName = "mcp_" + server.getName() + "_critical";
    AgentDefinitionDTO created =
        definitionService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentDefinitionUpdateDTO left = update(created);
    left.setDescription("left");
    left.getConfig().setSkills(List.of(skill(packageName, "dev")));
    left.getConfig().setTools(List.of(toolName));
    AgentDefinitionUpdateDTO right = update(created);
    right.setDescription("right");
    right.getConfig().setSkills(List.of(skill(packageName, "dev")));
    right.getConfig().setTools(List.of(toolName));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<AgentDefinitionDTO> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return definitionService.updateAgent(created.getName(), left);
              });
      Future<AgentDefinitionDTO> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return definitionService.updateAgent(created.getName(), right);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      List<Object> results = List.of(outcome(first), outcome(second));
      // 两个事务使用同一个 expectedVersion：一个 CAS 成功，另一个确定性冲突；任一锁等待或死锁都会让 get 超时。
      assertEquals(
          1,
          results.stream().filter(AgentDefinitionDTO.class::isInstance).count(),
          results::toString);
      assertEquals(
          1,
          results.stream().filter(AiVersionConflictException.class::isInstance).count(),
          results::toString);
      AgentDefinition persisted = definitionRepository.getByName(created.getName());
      definitionService.deleteAgent(persisted.getName(), String.valueOf(persisted.getVersion()));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    deletePackage(packageName, 0);
    mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    model.delete();
  }

  private <T> Outcome<T> race(
      Callable<T> agentWrite, Supplier<Object> holdLifecycleLock, Callable<Object> lifecycleWrite)
      throws Exception {
    CountDownLatch lifecycleLocked = new CountDownLatch(1);
    CountDownLatch agentWaiting = new CountDownLatch(1);
    CountDownLatch releaseLifecycle = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AtomicReference<Future<T>> agent = new AtomicReference<>();
    try {
      Future<?> lifecycle =
          executor.submit(
              () ->
                  transaction.execute(
                      status -> {
                        holdLifecycleLock.get();
                        lifecycleLocked.countDown();
                        await(agentWaiting);
                        await(releaseLifecycle);
                        try {
                          return lifecycleWrite.call();
                        } catch (Exception error) {
                          if (error instanceof RuntimeException runtime) {
                            throw runtime;
                          }
                          throw new IllegalStateException(error);
                        }
                      }));
      assertTrue(lifecycleLocked.await(5, TimeUnit.SECONDS));
      agent.set(executor.submit(agentWrite::call));
      awaitLockWaiter();
      agentWaiting.countDown();
      releaseLifecycle.countDown();
      lifecycle.get(5, TimeUnit.SECONDS);
      return new Outcome<>(agent.get().get(5, TimeUnit.SECONDS), null);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (agent.get() != null && !agent.get().isDone()) {
        releaseLifecycle.countDown();
        try {
          return new Outcome<>(agent.get().get(5, TimeUnit.SECONDS), cause);
        } catch (ExecutionException agentError) {
          return new Outcome<>(null, agentError.getCause());
        }
      }
      return new Outcome<>(null, cause);
    } finally {
      agentWaiting.countDown();
      releaseLifecycle.countDown();
      executor.shutdownNow();
    }
  }

  /** 等待 Agent 事务已经在生命周期行锁上阻塞，而不是只是提交了线程。 */
  private void awaitLockWaiter() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Integer waiters =
          jdbc.queryForObject(
              """
              select count(*)
              from pg_locks
              where granted = false and locktype in ('tuple', 'transactionid')
              """,
              Integer.class);
      if (waiters != null && waiters > 0) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    }
    throw new AssertionError("agent transaction did not wait for the lifecycle lock");
  }

  private static Object outcome(Future<?> future) throws Exception {
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      return error.getCause();
    }
  }

  private void skillServiceDelete(String packageName) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          SkillPackage locked = skillPackages.lockPackage(packageName);
          if (locked != null) {
            skillPackages.deletePackage(packageName, locked.getVersion());
          }
        });
  }

  private void insertPackage(String packageName, String... skillNames) {
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(packageName);
    skillPackage.setRepositoryUrl("https://example.com/" + packageName + ".git");
    skillPackage.setBranch("main");
    skillPackage.setCurrentCommit(COMMIT);
    skillPackage.setObservedHeadCommit(COMMIT);
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, skillName + " skill"));
    }
    skillPackage.setSkills(skills);
    skillPackage.setVersion(0L);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    Boolean inserted = transaction.execute(status -> skillPackages.insertPackage(skillPackage));
    assertTrue(inserted);
  }

  /** Skill Package 写路径要求调用方事务：包一层短事务，使成功写与通知共用同一 Connection。 */
  private void deletePackage(String packageName, long expectedVersion) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> skillPackages.deletePackage(packageName, expectedVersion));
  }

  private void replaceManifest(String packageName, String... skillNames) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          SkillPackage current = skillPackages.lockPackage(packageName);
          List<SkillManifestEntry> skills = new ArrayList<>();
          for (String skillName : skillNames) {
            skills.add(new SkillManifestEntry(skillName, skillName + " skill"));
          }
          current.setSkills(skills);
          assertTrue(skillPackages.updatePackage(current, current.getVersion()));
        });
  }

  private McpServerDTO mcp(String name) {
    McpServerCreateDTO create = new McpServerCreateDTO();
    create.setName(name);
    create.setUrl("https://example.com/" + name);
    create.setTimeoutMillis(5000L);
    return mcpServerService.createServer(create);
  }

  private void insertTool(String serverName, String sourceName) {
    McpTool tool = new McpTool();
    tool.setName("mcp_" + serverName + "_" + sourceName);
    tool.setServerName(serverName);
    tool.setSourceName(sourceName);
    tool.setDescription(sourceName + " tool");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
    mcpServers.insertTool(tool);
    jdbc.update("update mcp_server set discovery_status = 'AVAILABLE' where name = ?", serverName);
  }

  private String currentServerVersion(String name) {
    return String.valueOf(mcpServers.getByName(name).orElseThrow().getVersion());
  }

  private Model model(String suffix) {
    AgentProviderCreateDTO provider = new AgentProviderCreateDTO();
    provider.setName("provider-" + suffix);
    provider.setProviderType("openai");
    AgentProviderDTO createdProvider = providerService.createProvider(provider);
    AgentModelCreateDTO model = new AgentModelCreateDTO();
    model.setProviderName(createdProvider.getName());
    model.setName("model-" + suffix);
    executable(model);
    AgentModelDTO createdModel = modelService.createModel(model);
    return new Model(createdProvider, createdModel);
  }

  private AgentDefinitionCreateDTO agent(String modelRef, String name) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(name);
    create.setModel(modelRef);
    create.setVariant("default");
    create.setConfig(config);
    return create;
  }

  private AgentDefinitionUpdateDTO update(AgentDefinitionDTO current) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(new ArrayList<>(current.getConfig().getTools()));
    config.setSkills(new ArrayList<>(current.getConfig().getSkills()));
    config.setSubagents(new ArrayList<>(current.getConfig().getSubagents()));
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription(current.getDescription());
    update.setSystemPrompt(current.getSystemPrompt());
    update.setModel(current.getModel());
    update.setVariant(current.getVariant());
    update.setConfig(config);
    update.setExpectedVersion(current.getVersion());
    return update;
  }

  private static SkillRefDTO skill(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private static String suffix() {
    return Long.toString(System.nanoTime(), 36);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private record Outcome<T>(T value, Throwable error) {}

  private final class Model {
    private final AgentProviderDTO provider;
    private final AgentModelDTO model;

    private Model(AgentProviderDTO provider, AgentModelDTO model) {
      this.provider = provider;
      this.model = model;
    }

    private String ref() {
      return provider.getName() + "/" + model.getName();
    }

    private void delete() {
      modelService.deleteModel(provider.getName(), model.getName(), model.getVersion());
      providerService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }
}
