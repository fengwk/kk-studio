package fun.fengwk.kkstudio.platform.catalog;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelTestData.executable;
import static fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageGitSource;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  @Autowired private AgentService agentService;
  @Autowired private AgentRepository agentRepository;
  @Autowired private ProviderService providerService;
  @Autowired private ModelService modelService;
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
    AgentCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setSkills(List.of(skill(packageName, "dev")));

    Outcome<AgentDTO> created =
        race(
            () -> agentService.createAgent(create),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              skillServiceDelete(packageName);
              return null;
            });

    Agent persisted = agentRepository.getByName(create.getName());
    if (created.value() != null) {
      assertNotNull(persisted);
      assertNotNull(skillPackages.getPackage(packageName));
      agentService.deleteAgent(created.value().getName(), created.value().getVersion());
    } else {
      assertTrue(domainFailure(created.error(), DomainErrorCode.VALIDATION));
      if (persisted != null) {
        agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
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
    AgentDTO created = agentService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentUpdateDTO update = update(created);
    update.getConfig().setSkills(List.of(skill(packageName, "review")));

    Outcome<AgentDTO> updated =
        race(
            () -> agentService.updateAgent(created.getName(), update),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              replaceManifest(packageName, "dev");
              return null;
            });

    Agent persisted = agentRepository.getByName(created.getName());
    boolean referencesReview = persisted.configJson().contains("\"name\": \"review\"");
    if (referencesReview) {
      assertNotNull(skillPackages.getPackage(packageName).findSkill("review"));
    }
    assertTrue(
        updated.value() != null || domainFailure(updated.error(), DomainErrorCode.VALIDATION));
    agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
    if (skillPackages.getPackage(packageName) != null) {
      deletePackage(packageName, skillPackages.getPackage(packageName).version());
    }
    model.delete();
  }

  @Test
  void createAgentCannotLeaveSecondSkillDanglingAfterPartialManifestRemoval() throws Exception {
    String suffix = suffix();
    Model model = model("skill-second-" + suffix);
    String packageName = "pkg" + suffix;
    insertPackage(packageName, "alpha", "zeta");
    AgentCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setSkills(List.of(skill(packageName, "alpha"), skill(packageName, "zeta")));

    Outcome<AgentDTO> created =
        race(
            () -> agentService.createAgent(create),
            () -> {
              skillPackages.lockPackage(packageName);
              return null;
            },
            () -> {
              replaceManifest(packageName, "alpha");
              return null;
            });

    Agent persisted = agentRepository.getByName(create.getName());
    if (persisted != null && persisted.configJson().contains("\"name\": \"zeta\"")) {
      assertNotNull(skillPackages.getPackage(packageName).findSkill("zeta"));
    }
    assertTrue(
        created.value() != null || domainFailure(created.error(), DomainErrorCode.VALIDATION));
    if (persisted != null) {
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
    }
    if (skillPackages.getPackage(packageName) != null) {
      deletePackage(packageName, skillPackages.getPackage(packageName).version());
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
    AgentCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(toolName));

    Outcome<AgentDTO> created =
        race(
            () -> agentService.createAgent(create),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              mcpServers.deleteTools(server.getName());
              return null;
            });

    Agent persisted = agentRepository.getByName(create.getName());
    if (persisted != null && persisted.configJson().contains(toolName)) {
      assertTrue(
          mcpServers.getTool(toolName).isPresent(),
          () -> created.value() + " / " + created.error() + " / " + persisted.configJson());
    }
    if (persisted != null) {
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
    }
    assertTrue(
        created.value() != null
            || domainFailure(created.error(), DomainErrorCode.VALIDATION)
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
    AgentCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(alpha, zeta));

    Outcome<AgentDTO> created =
        race(
            () -> agentService.createAgent(create),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              jdbc.update("delete from mcp_tool where name = ?", zeta);
              return null;
            });

    Agent persisted = agentRepository.getByName(create.getName());
    if (persisted != null && persisted.configJson().contains(zeta)) {
      assertTrue(mcpServers.getTool(zeta).isPresent(), () -> persisted.configJson());
    }
    assertTrue(
        created.value() != null
            || domainFailure(created.error(), DomainErrorCode.VALIDATION)
            || persisted == null);
    if (persisted != null) {
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
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
    AgentCreateDTO create = agent(model.ref(), "agent-" + suffix);
    create.getConfig().setTools(List.of(toolName));

    race(
        () -> agentService.createAgent(create),
        () -> {
          mcpServers.getForUpdate(server.getName());
          return null;
        },
        () -> {
          jdbc.update("delete from mcp_tool where name = ?", toolName);
          return null;
        });

    Agent persisted = agentRepository.getByName(create.getName());
    if (persisted != null && persisted.configJson().contains(toolName)) {
      assertTrue(mcpServers.getTool(toolName).isPresent());
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
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
    AgentDTO created = agentService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentUpdateDTO update = update(created);
    update.getConfig().setTools(List.of(toolName));

    Outcome<AgentDTO> updated =
        race(
            () -> agentService.updateAgent(created.getName(), update),
            () -> {
              mcpServers.getForUpdate(server.getName());
              return null;
            },
            () -> {
              try {
                mcpServerService.deleteServer(server.getName(), server.getVersion());
              } catch (DomainException error) {
                if (error.code() != DomainErrorCode.IN_USE) {
                  throw error;
                }
                // Agent 已经在共享锁内写入引用：删除必须失败并保留 server。
              }
              return null;
            });

    Agent persisted = agentRepository.getByName(created.getName());
    if (persisted.configJson().contains(toolName)) {
      assertTrue(mcpServers.getByName(server.getName()).isPresent());
      assertTrue(mcpServers.getTool(toolName).isPresent());
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
      mcpServerService.deleteServer(server.getName(), currentServerVersion(server.getName()));
    } else {
      assertTrue(
          domainFailure(updated.error(), DomainErrorCode.VALIDATION) || updated.value() != null);
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
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
    AgentDTO created = agentService.createAgent(agent(model.ref(), "agent-" + suffix));
    AgentUpdateDTO left = update(created);
    left.setDescription("left");
    left.getConfig().setSkills(List.of(skill(packageName, "dev")));
    left.getConfig().setTools(List.of(toolName));
    AgentUpdateDTO right = update(created);
    right.setDescription("right");
    right.getConfig().setSkills(List.of(skill(packageName, "dev")));
    right.getConfig().setTools(List.of(toolName));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<AgentDTO> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return agentService.updateAgent(created.getName(), left);
              });
      Future<AgentDTO> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return agentService.updateAgent(created.getName(), right);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      List<Object> results = List.of(outcome(first), outcome(second));
      // 两个事务使用同一个 expectedVersion：一个 CAS 成功，另一个确定性冲突；任一锁等待或死锁都会让 get 超时。
      assertEquals(
          1, results.stream().filter(AgentDTO.class::isInstance).count(), results::toString);
      assertEquals(
          1,
          results.stream()
              .filter(
                  error ->
                      error instanceof DomainException domain
                          && domain.code() == DomainErrorCode.VERSION_CONFLICT)
              .count(),
          results::toString);
      Agent persisted = agentRepository.getByName(created.getName());
      agentService.deleteAgent(persisted.name(), String.valueOf(persisted.version()));
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
            skillPackages.deletePackage(packageName, locked.version());
          }
        });
  }

  private UUID insertArtifactBlob(String packageName) {
    UUID blobId =
        UUID.nameUUIDFromBytes(("artifact:" + packageName).getBytes(StandardCharsets.UTF_8));
    String sha256 = String.format("%064x", Math.abs((long) packageName.hashCode()));
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, 1, 'application/zip', 1, 'ACTIVE')"
            + " on conflict do nothing",
        blobId,
        sha256);
    return blobId;
  }

  private void insertPackage(String packageName, String... skillNames) {
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, skillName + " skill"));
    }
    UUID artifactBlobId = insertArtifactBlob(packageName);
    SkillPackage skillPackage =
        new SkillPackage(
            packageName,
            null,
            COMMIT,
            artifactBlobId,
            skills,
            Instant.now(),
            0L,
            null,
            null,
            new SkillPackageGitSource(
                "https://example.com/" + packageName + ".git",
                "main",
                null,
                COMMIT,
                Instant.now(),
                null));
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
          SkillPackage updated =
              new SkillPackage(
                  current.packageName(),
                  current.description(),
                  current.packageVersion(),
                  current.artifactBlobId(),
                  skills,
                  Instant.now(),
                  current.version(),
                  current.createdAt(),
                  current.updatedAt(),
                  current.gitSource());
          assertTrue(skillPackages.updatePublication(updated, current.version()));
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
    McpTool tool =
        McpTool.builder()
            .name("mcp_" + serverName + "_" + sourceName)
            .serverName(serverName)
            .sourceName(sourceName)
            .description(sourceName + " tool")
            .inputSchemaJson(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")
            .build();
    mcpServers.insertTool(tool);
    jdbc.update("update mcp_server set discovery_status = 'AVAILABLE' where name = ?", serverName);
  }

  private String currentServerVersion(String name) {
    return String.valueOf(mcpServers.getByName(name).orElseThrow().version());
  }

  private Model model(String suffix) {
    ProviderCreateDTO provider = new ProviderCreateDTO();
    provider.setName("provider-" + suffix);
    provider.setProviderType(ProviderType.OPENAI);
    ProviderDTO createdProvider = providerService.createProvider(provider);
    SingleModelCreateDTO model = new SingleModelCreateDTO();
    model.setProviderName(createdProvider.getName());
    model.setName("model-" + suffix);
    executable(model);
    ModelDTO createdModel = modelService.createModel(model);
    return new Model(createdProvider, createdModel);
  }

  private AgentCreateDTO agent(String modelRef, String name) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    AgentCreateDTO create = new AgentCreateDTO();
    create.setName(name);
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver(BuiltinExecution.DRIVER);
    execution.setSettings(jsonObject(Map.of("modelName", modelRef, "variant", "default")));
    create.setExecution(execution);
    create.setConfig(config);
    return create;
  }

  private AgentUpdateDTO update(AgentDTO current) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(new ArrayList<>(current.getConfig().getTools()));
    config.setSkills(new ArrayList<>(current.getConfig().getSkills()));
    config.setSubagents(new ArrayList<>(current.getConfig().getSubagents()));
    AgentUpdateDTO update = new AgentUpdateDTO();
    update.setDescription(current.getDescription());
    update.setSystemPrompt(current.getSystemPrompt());
    update.setExecution(current.getExecution());
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

  private static boolean domainFailure(Throwable error, DomainErrorCode code) {
    return error instanceof DomainException domain && domain.code() == code;
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
    private final ProviderDTO provider;
    private final ModelDTO model;

    private Model(ProviderDTO provider, ModelDTO model) {
      this.provider = provider;
      this.model = model;
    }

    private String ref() {
      return model.getName();
    }

    private void delete() {
      modelService.deleteModel(model.getName(), model.getVersion());
      providerService.deleteProvider(provider.getName(), provider.getVersion());
    }
  }
}
