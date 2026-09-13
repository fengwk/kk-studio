package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper.EnvironmentSkillSourceMapper;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Skill 来源 CRUD 与集合代际在真实 PostgreSQL 上的契约。
 *
 * <p>覆盖：创建 Environment 的原子默认初始化、集合代际只在成员关系变化时推进、编辑语义（UNAPPLIED + 保留 applied 事实 + 清理 error +
 * 不动集合代际）、默认来源可删且不自动补回、CAS 并发、FK 级联，以及 inventory 行随 Environment 删除。
 */
class SkillSourceCrudIntegrationTest extends PostgresSpringTestSupport {

  private static final String DEFAULT_SOURCE_PATH = "~/.agents/skills";

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private EnvironmentSkillSourceMapper sourceMapper;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private EnvironmentRepository environmentRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  private EnvironmentId environmentId;

  @BeforeEach
  void createEnvironment() {
    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("source-env-" + System.nanoTime());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(create).getId()));
  }

  /**
   * 意图：创建 Environment 必须在同一事务内产生 inventory 行（代际 0）与缺省 PATH 来源（version 0/UNAPPLIED/default=true）。
   */
  @Test
  void environmentCreationAtomicallyCreatesInventoryAndDefaultSource() {
    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    assertNotNull(inventory, "创建 Environment 必须原子创建 inventory 行");
    assertEquals(0L, inventory.getSourceSetVersion());
    assertNull(inventory.getAppliedSourceSetVersion());
    assertNull(inventory.getRootPath(), "没有已接受报告时全部报告列必须为空");

    List<EnvironmentSkillSourceDTO> sources = sourceService.list(environmentId);
    assertEquals(1, sources.size());
    EnvironmentSkillSourceDTO defaultSource = sources.get(0);
    assertEquals("path", defaultSource.getType());
    assertEquals(DEFAULT_SOURCE_PATH, defaultSource.getPath());
    assertTrue(defaultSource.isDefaultSource());
    assertEquals("0", defaultSource.getVersion());
    assertEquals("UNAPPLIED", defaultSource.getStatus());
    assertNull(defaultSource.getAppliedVersion());
    assertNull(defaultSource.getLastErrorCode());
    assertTrue(defaultSource.getDiagnostics().isEmpty());
  }

  /** 意图：创建/删除来源推进集合代际，而编辑来源配置不改变成员关系，因此代际保持不变。 */
  @Test
  void sourceSetVersionAdvancesOnlyOnMembershipChange() {
    long initial = inventory().getSourceSetVersion();

    EnvironmentSkillSourceDTO git = sourceService.create(environmentId, gitSource("extras"));
    assertEquals(initial + 1, inventory().getSourceSetVersion(), "创建来源必须推进集合代际");

    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("git");
    update.setGitUrl("https://example.test/other.git");
    update.setGitRef("release");
    update.setExpectedVersion(git.getVersion());
    sourceService.update(environmentId, UUID.fromString(git.getSourceId()), update);
    assertEquals(initial + 1, inventory().getSourceSetVersion(), "编辑配置不改变集合成员关系");

    sourceService.delete(environmentId, UUID.fromString(git.getSourceId()), "1");
    assertEquals(initial + 2, inventory().getSourceSetVersion(), "删除来源必须推进集合代际");
  }

  /** 意图：编辑把状态置 UNAPPLIED、清空诊断/错误、推进版本，但保留既有 applied 三元组作为陈旧展示事实。 */
  @Test
  void updateResetsStateKeepsAppliedFactsAndBumpsVersion() {
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());
    // 直接写入"已成功应用"事实：模拟一次被接受的 READY 发布。
    jdbcTemplate.update(
        "update environment_skill_source set status = 'READY', applied_version = version,"
            + " applied_revision = ?, last_applied_at = current_timestamp,"
            + " diagnostics = cast(? as jsonb) where source_id = ?",
        "0".repeat(40),
        "[{\"location\":\"/old\",\"message\":\"old note\"}]",
        sourceId);
    jdbcTemplate.update(
        "update environment_skill_source set status = 'FAILED', last_error_code = 'IO',"
            + " last_error_message = 'boom', applied_version = 0 where source_id = ?",
        sourceId);

    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("path");
    update.setPath("/srv/skills");
    update.setExpectedVersion(source.getVersion());
    EnvironmentSkillSourceDTO updated = sourceService.update(environmentId, sourceId, update);

    assertEquals("1", updated.getVersion());
    assertEquals("UNAPPLIED", updated.getStatus());
    assertEquals("0", updated.getAppliedVersion(), "旧 applied 事实作为陈旧展示事实保留");
    assertEquals("0".repeat(40), updated.getAppliedRevision());
    assertNull(updated.getLastErrorCode(), "编辑必须清空 last error");
    assertNull(updated.getLastErrorMessage());
    assertTrue(updated.getDiagnostics().isEmpty(), "编辑必须清空旧诊断");
    assertEquals("/srv/skills", updated.getPath());
  }

  /** 意图：缺省来源同样可以删除；删除后空集合就是明确的"禁用发现"，绝不自动补回缺省来源。 */
  @Test
  void defaultSourceMayBeDeletedAndIsNeverRecreated() {
    EnvironmentSkillSourceDTO defaultSource =
        sourceService.list(environmentId).stream()
            .filter(EnvironmentSkillSourceDTO::isDefaultSource)
            .findFirst()
            .orElseThrow();

    sourceService.delete(
        environmentId, UUID.fromString(defaultSource.getSourceId()), defaultSource.getVersion());

    assertTrue(sourceService.list(environmentId).isEmpty());
    assertFalse(sourceMapper.existsDefaultByEnvironment(environmentId.value()), "删除缺省来源后不得自动补回");
    assertEquals(1L, inventory().getSourceSetVersion());
  }

  /** 意图：CAS 冲突必须映射为 409 语义错误，并携带真实 actual 版本；陈旧版本不得写库。 */
  @Test
  void updateAndDeleteRejectStaleVersion() {
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());
    // 并发推进版本：先做一次成功编辑，使版本变 1。
    EnvironmentSkillSourceUpdateDTO first = new EnvironmentSkillSourceUpdateDTO();
    first.setType("path");
    first.setPath("/srv/skills");
    first.setExpectedVersion(source.getVersion());
    sourceService.update(environmentId, sourceId, first);

    EnvironmentSkillSourceUpdateDTO stale = new EnvironmentSkillSourceUpdateDTO();
    stale.setType("path");
    stale.setPath("/srv/other");
    stale.setExpectedVersion("0");
    AiVersionConflictException updateError =
        assertThrows(
            AiVersionConflictException.class,
            () -> sourceService.update(environmentId, sourceId, stale));
    assertEquals("0", updateError.expectedVersion());
    assertEquals("1", updateError.actualVersion());
    assertEquals("/srv/skills", sourceService.get(environmentId, sourceId).getPath());

    AiVersionConflictException deleteError =
        assertThrows(
            AiVersionConflictException.class,
            () -> sourceService.delete(environmentId, sourceId, "0"));
    assertEquals("1", deleteError.actualVersion());
  }

  /** 意图：并发 CAS 只有一个赢家，输者必须拿到版本冲突而不是静默覆盖。 */
  @Test
  void concurrentCasHasExactlyOneWinner() throws Exception {
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> attempt =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
            update.setType("path");
            update.setPath("/srv/skills");
            update.setExpectedVersion("0");
            try {
              sourceService.update(environmentId, sourceId, update);
              return true;
            } catch (AiVersionConflictException error) {
              return false;
            }
          };
      Future<Boolean> first = executor.submit(attempt);
      Future<Boolean> second = executor.submit(attempt);
      start.countDown();
      boolean firstWon = first.get(15, TimeUnit.SECONDS);
      boolean secondWon = second.get(15, TimeUnit.SECONDS);

      assertTrue(firstWon ^ secondWon, "并发行版本 CAS 必须恰好一个成功");
      assertEquals("1", sourceService.get(environmentId, sourceId).getVersion());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 意图：删除来源级联删除其持久 inventory 行，且不留下悬空行。 */
  @Test
  void deleteCascadesSkillRows() {
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());
    insertSkill(environmentId.value(), sourceId, "dev");
    assertEquals(1, countSkills(sourceId));

    sourceService.delete(environmentId, sourceId, source.getVersion());

    assertEquals(0, countSkills(sourceId), "删除来源必须级联删除其 skill 行");
  }

  /** 意图：删除 Environment 级联清空 inventory 与来源配置，不留下孤儿行。 */
  @Test
  void environmentDeletionRemovesSourceAndInventoryRows() {
    UUID environmentValue = environmentId.value();
    String version = environmentService.get(environmentId).getVersion();
    environmentService.delete(environmentId, version);

    assertNull(skillSourceRepository.getInventory(environmentValue));
    assertTrue(skillSourceRepository.listSources(environmentValue).isEmpty());
    assertEquals(0, countSources(environmentValue));
  }

  /** 意图：不存在的 Environment / 来源必须映射为稳定的 404 语义错误。 */
  @Test
  void missingEnvironmentAndSourceAreNotFound() {
    EnvironmentId missing = EnvironmentId.of(UUID.randomUUID());
    assertThrows(AiResourceNotFoundException.class, () -> sourceService.list(missing));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> sourceService.get(environmentId, UUID.randomUUID()));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> sourceService.delete(environmentId, UUID.randomUUID(), "0"));
    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("path");
    update.setPath("/srv/skills");
    update.setExpectedVersion("0");
    assertThrows(
        AiResourceNotFoundException.class,
        () -> sourceService.update(environmentId, UUID.randomUUID(), update));
  }

  /** 意图：非法来源配置在进入数据库前就被拒绝，环境状态完全不变。 */
  @Test
  void invalidConfigurationIsRejectedBeforePersistence() {
    EnvironmentSkillSourceCreateDTO invalid = new EnvironmentSkillSourceCreateDTO();
    invalid.setType("path");
    invalid.setPath("relative/skills");
    assertThrows(AiValidationException.class, () -> sourceService.create(environmentId, invalid));

    EnvironmentSkillSourceCreateDTO wrongType = new EnvironmentSkillSourceCreateDTO();
    wrongType.setType("git");
    wrongType.setGitUrl("https://example.test/repo.git");
    wrongType.setScanPath("skills/../outside");
    assertThrows(AiValidationException.class, () -> sourceService.create(environmentId, wrongType));

    assertEquals(1, sourceService.list(environmentId).size());
    assertEquals(0L, inventory().getSourceSetVersion());
  }

  /** 意图：缺省来源不能被修改为 git 类型（schema 强约束 default_source 仅 path 可为 true），结构化校验在 SQL 前拒绝，不泄漏任何凭据。 */
  @Test
  void defaultSourceCannotBeUpdatedToGit() {
    EnvironmentSkillSourceDTO defaultSource =
        sourceService.list(environmentId).stream()
            .filter(EnvironmentSkillSourceDTO::isDefaultSource)
            .findFirst()
            .orElseThrow();
    UUID sourceId = UUID.fromString(defaultSource.getSourceId());

    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("git");
    update.setGitUrl("https://user:SECRET_CREDENTIAL@example.test/repo.git");
    update.setGitRef("main");
    update.setExpectedVersion(defaultSource.getVersion());

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () -> sourceService.update(environmentId, sourceId, update));
    assertEquals("default skill source cannot be changed to git", error.getMessage());
    assertNull(error.getCause(), "结构化校验直接拒绝，不产生底层 JDBC 异常");
    assertFalse(error.getMessage().contains("SECRET_CREDENTIAL"));

    // 确认来源状态未被触碰，仍然是 path 类型、版本为 0
    EnvironmentSkillSourceDTO unchanged = sourceService.get(environmentId, sourceId);
    assertEquals("path", unchanged.getType());
    assertEquals("0", unchanged.getVersion());
  }

  /** 意图：读取顺序必须稳定（按 source_id 升序），与插入顺序无关。 */
  @Test
  void listingIsDeterministicallyOrdered() {
    sourceService.create(environmentId, pathSource("/srv/a"));
    sourceService.create(environmentId, pathSource("/srv/b"));
    sourceService.create(environmentId, pathSource("/srv/c"));

    List<EnvironmentSkillSourceDTO> first = sourceService.list(environmentId);
    List<EnvironmentSkillSourceDTO> second = sourceService.list(environmentId);

    assertEquals(
        first.stream().map(EnvironmentSkillSourceDTO::getSourceId).toList(),
        second.stream().map(EnvironmentSkillSourceDTO::getSourceId).toList());
    List<String> ids = first.stream().map(EnvironmentSkillSourceDTO::getSourceId).toList();
    List<String> sorted = ids.stream().sorted().toList();
    assertEquals(sorted, ids, "来源必须按 source_id 升序返回");
  }

  private EnvironmentInventory inventory() {
    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    assertNotNull(inventory);
    return inventory;
  }

  private EnvironmentSkillSourceCreateDTO gitSource(String scanPath) {
    EnvironmentSkillSourceCreateDTO create = new EnvironmentSkillSourceCreateDTO();
    create.setType("git");
    create.setGitUrl("https://example.test/repo.git");
    create.setGitRef("main");
    create.setScanPath(scanPath);
    return create;
  }

  private EnvironmentSkillSourceCreateDTO pathSource(String path) {
    EnvironmentSkillSourceCreateDTO create = new EnvironmentSkillSourceCreateDTO();
    create.setType("path");
    create.setPath(path);
    return create;
  }

  private void insertSkill(UUID environmentValue, UUID sourceId, String name) {
    jdbcTemplate.update(
        "insert into environment_skill (environment_id, source_id, name, source_version,"
            + " description, base_directory, content_revision, discovered_at)"
            + " values (?, ?, ?, 0, ?, ?, ?, current_timestamp)",
        environmentValue,
        sourceId,
        name,
        name + " skill",
        "/home/dev/skills/" + name,
        "0".repeat(64));
  }

  private int countSkills(UUID sourceId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from environment_skill where source_id = ?", Integer.class, sourceId);
    return count == null ? 0 : count;
  }

  private void ensureTestModel() {
    jdbcTemplate.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values ('test_p', 'openai', '{}'::jsonb, '00000000-0000-0000-0000-000000000001'::uuid) on conflict do nothing");
    jdbcTemplate.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values ('test_p', 'test_m', 'test_m', '{}'::jsonb) on conflict do nothing");
  }

  /**
   * 测试意图：验证 PostgreSQL 中基于 JSONB lateral elements 的 existsReferencingSkillSource 查询， 必须精确匹配
   * environment_id 与 skills 数组中每个对象的 sourceId，不同环境或未被引用的 sourceId 返回 false。
   */
  @Test
  void existsReferencingSkillSourceExactEnvironmentAndSourceMatching() {
    ensureTestModel();
    UUID env1 = environmentId.value();
    UUID sourceA = UUID.randomUUID();
    UUID sourceB = UUID.randomUUID();
    UUID sourceC = UUID.randomUUID();

    EnvironmentCreateDTO otherCreate = new EnvironmentCreateDTO();
    otherCreate.setName("other-env-" + System.nanoTime());
    UUID env2 = UUID.fromString(environmentService.create(otherCreate).getId());

    // 在 env1 中创建 Agent，引用 sourceA 与 sourceB
    String configJson =
        "{\"toolIds\":[],\"skills\":["
            + "{\"sourceId\":\""
            + sourceA
            + "\",\"name\":\"skill-a\"},"
            + "{\"sourceId\":\""
            + sourceB
            + "\",\"name\":\"skill-b\"}"
            + "],\"subagents\":[]}";
    jdbcTemplate.update(
        "insert into agent_definition (name, model_provider_name, model_name, environment_id, config) "
            + "values (?, 'test_p', 'test_m', ?, ?::jsonb)",
        "agent-in-env1",
        env1,
        configJson);

    // 在 env2 中创建 Agent，引用空 skills
    jdbcTemplate.update(
        "insert into agent_definition (name, model_provider_name, model_name, environment_id, config) "
            + "values (?, 'test_p', 'test_m', ?, ?::jsonb)",
        "agent-in-env2",
        env2,
        "{\"toolIds\":[],\"skills\":[],\"subagents\":[]}");

    // env1 应该精确命中 sourceA 与 sourceB，但不命中 sourceC
    assertTrue(agentDefinitionRepository.existsReferencingSkillSource(env1, sourceA));
    assertTrue(agentDefinitionRepository.existsReferencingSkillSource(env1, sourceB));
    assertFalse(agentDefinitionRepository.existsReferencingSkillSource(env1, sourceC));

    // env2 不应该命中 sourceA
    assertFalse(agentDefinitionRepository.existsReferencingSkillSource(env2, sourceA));
  }

  /**
   * 测试意图：验证当某个 SkillSource 被当前环境的 Agent 引用时，调用 sourceService.delete 必须抛出 AiInUseException 且数据不被删除。
   */
  @Test
  void deleteSourceFailsWithAiInUseExceptionWhenReferencedByAgent() {
    ensureTestModel();
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());

    // 插入引用该 sourceId 的 AgentDefinition
    String configJson =
        "{\"toolIds\":[],\"skills\":[{\"sourceId\":\""
            + sourceId
            + "\",\"name\":\"default-skill\"}],\"subagents\":[]}";
    jdbcTemplate.update(
        "insert into agent_definition (name, model_provider_name, model_name, environment_id, config) "
            + "values (?, 'test_p', 'test_m', ?, ?::jsonb)",
        "agent-referencing-source-" + System.nanoTime(),
        environmentId.value(),
        configJson);

    AiInUseException ex =
        assertThrows(
            AiInUseException.class,
            () -> sourceService.delete(environmentId, sourceId, source.getVersion()));
    assertTrue(ex.getMessage().contains("referenced by an agent"));

    // 验证该 source 依然完好存在
    assertNotNull(sourceService.get(environmentId, sourceId));
  }

  /**
   * 测试意图：证明 Agent 引用校验（获取 inventory + source 锁）与来源删除（同样获取 inventory + source 锁） 在真实 PostgreSQL
   * 上由于锁排他互斥，无法发生竞态穿透：持有锁时尝试删除会被阻塞，事务提交后删除检测到新引用确定性抛出 AiInUseException。
   */
  @Test
  void concurrentAgentCreateReferenceAndSourceDeleteSerializeOnSharedLocks() throws Exception {
    ensureTestModel();
    EnvironmentSkillSourceDTO source = sourceService.list(environmentId).get(0);
    UUID sourceId = UUID.fromString(source.getSourceId());

    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // 线程 1：模拟 Agent 保存流程获取锁
      Future<Void> agentThread =
          executor.submit(
              () -> {
                transactionTemplate.execute(
                    status -> {
                      // 按照引用锁协议取锁
                      environmentRepository.lockForKeyShare(environmentId.value());
                      skillSourceRepository.lockInventory(environmentId.value());
                      skillSourceRepository.lockAllSources(environmentId.value());

                      lockAcquired.countDown();

                      // 等待删除线程已启动并进入等待锁状态
                      try {
                        deleteStarted.await(5, TimeUnit.SECONDS);
                        Thread.sleep(100); // 确保删除线程已在 DB 等待行锁
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }

                      // 在事务中插入引用了 sourceId 的 Agent
                      String configJson =
                          "{\"toolIds\":[],\"skills\":[{\"sourceId\":\""
                              + sourceId
                              + "\",\"name\":\"dev\"}],\"subagents\":[]}";
                      jdbcTemplate.update(
                          "insert into agent_definition (name, model_provider_name, model_name, environment_id, config) "
                              + "values (?, 'test_p', 'test_m', ?, ?::jsonb)",
                          "agent-concurrent-" + System.nanoTime(),
                          environmentId.value(),
                          configJson);
                      return null;
                    });
                return null;
              });

      // 线程 2：尝试删除 source
      Future<Throwable> deleteThread =
          executor.submit(
              () -> {
                try {
                  lockAcquired.await(5, TimeUnit.SECONDS);
                  deleteStarted.countDown();
                  sourceService.delete(environmentId, sourceId, source.getVersion());
                  return null;
                } catch (Throwable t) {
                  return t;
                }
              });

      agentThread.get(10, TimeUnit.SECONDS);
      Throwable deleteError = deleteThread.get(10, TimeUnit.SECONDS);

      assertNotNull(deleteError, "删除操作在持有锁的事务提交后必须被拒绝");
      assertTrue(
          deleteError instanceof AiInUseException,
          "删除操作必须因检测到新创建的 Agent 引用而抛出 AiInUseException，但捕获到: " + deleteError);
      assertNotNull(sourceService.get(environmentId, sourceId), "SkillSource 必须依然存在于数据库中");
    } finally {
      executor.shutdownNow();
    }
  }

  private int countSources(UUID environmentValue) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from environment_skill_source where environment_id = ?",
            Integer.class,
            environmentValue);
    return count == null ? 0 : count;
  }
}
