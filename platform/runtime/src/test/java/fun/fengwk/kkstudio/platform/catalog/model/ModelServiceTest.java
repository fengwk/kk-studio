package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelTestData.buildConfig;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelTestData.executable;
import static fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncService;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 全局模型名称跨 MODEL/GROUP 唯一，并验证组 CRUD、引用维护与冷却清理。 */
public class ModelServiceTest extends PostgresSpringTestSupport {

  @Autowired private ProviderService agentProviderService;
  @Autowired private ModelService agentModelService;
  @Autowired private ModelRepository agentModelRepository;
  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ConfigSyncService configSyncService;

  @Test
  public void builtinReferencesAreRenamedButOtherDriversAreUntouched() {
    String suffix = Long.toString(System.nanoTime());
    String original = "reference-model-" + suffix;
    String renamed = "reference-renamed-" + suffix;
    String builtin = "builtin-ref-" + suffix;
    String other = "other-ref-" + suffix;
    String providerName = "reference-provider-" + suffix;
    provider(providerName);
    ModelDTO model = agentModelService.createModel(model(providerName, original));
    // 相同字段出现在其他 driver 中时不是 Builtin 模型引用。
    jdbc.update(
        "insert into agent(name, execution, config) values"
            + " (?, jsonb_build_object('driver', ?, 'settings',"
            + " jsonb_build_object('modelName', ?, 'variant', 'default')), '{}'::jsonb)",
        builtin,
        "builtin",
        original);
    jdbc.update(
        "insert into agent(name, execution, config) values"
            + " (?, jsonb_build_object('driver', ?, 'settings',"
            + " jsonb_build_object('modelName', ?, 'variant', 'default')), '{}'::jsonb)",
        other,
        "other",
        original);
    assertThrows(DomainException.class, () -> agentModelService.deleteModel(original, 0L));
    assertTrue(agentModelRepository.hasVariantReferences(original, "default"));
    assertFalse(agentModelRepository.hasVariantReferences(original, "other"));
    ModelDTO result =
        agentModelService.updateModel(original, modelUpdate(model, renamed, "renamed"));
    assertEquals(
        renamed,
        jdbc.queryForObject(
            "select execution -> 'settings' ->> 'modelName' from agent where name = ?",
            String.class,
            builtin));
    assertEquals(
        1L, jdbc.queryForObject("select version from agent where name = ?", Long.class, builtin));
    assertEquals(
        original,
        jdbc.queryForObject(
            "select execution -> 'settings' ->> 'modelName' from agent where name = ?",
            String.class,
            other));
    assertEquals(
        0L, jdbc.queryForObject("select version from agent where name = ?", Long.class, other));
    assertFalse(agentModelRepository.hasAgents(original));
    assertFalse(agentModelRepository.hasVariantReferences(original, "default"));
    ModelDTO recreated = agentModelService.createModel(model(providerName, original));
    agentModelService.deleteModel(original, recreated.getVersion());
    assertThrows(
        DomainException.class, () -> agentModelService.deleteModel(renamed, result.getVersion()));
    jdbc.update("delete from agent where name = ?", builtin);
    agentModelService.deleteModel(renamed, result.getVersion());
  }

  @Test
  public void shouldEnforceGlobalModelNamesAcrossProviders() {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = provider("model-provider-" + suffix);
    ProviderDTO otherProvider = provider("model-provider-other-" + suffix);
    String modelName = "shared-model-" + suffix;

    ModelDTO model = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals(provider.getName(), single(model).getProviderName());
    assertEquals(ModelKind.MODEL, kindOf(model));
    assertEquals(0L, model.getVersion());
    assertThrows(
        DomainException.class,
        () -> agentModelService.createModel(model(provider.getName(), modelName)));
    // 同一逻辑名称即使换 Provider 也不可重复。
    assertThrows(
        DomainException.class,
        () -> agentModelService.createModel(model(otherProvider.getName(), modelName)));

    assertThrows(
        DomainException.class,
        () -> agentModelService.createModel(model("missing-" + suffix, "missing-" + suffix)));
    assertThrows(
        DomainException.class,
        () -> agentModelService.createModel(model(" ", "invalid-" + suffix)));
    assertThrows(
        DomainException.class,
        () -> agentProviderService.deleteProvider(provider.getName(), provider.getVersion()));

    SingleModelUpdateDTO update = modelUpdate(model, modelName, "updated");
    ModelDTO updated = agentModelService.updateModel(modelName, update);
    assertEquals("updated", updated.getDescription());
    assertEquals(1L, updated.getVersion());
    assertTrue(
        agentModelService.listModels().stream()
            .anyMatch(candidate -> candidate.getName().equals(modelName)));

    SingleModelUpdateDTO stale = modelUpdate(model, modelName, "stale");
    stale.setExpectedVersion(model.getVersion());
    assertThrows(DomainException.class, () -> agentModelService.updateModel(modelName, stale));

    agentModelService.deleteModel(modelName, updated.getVersion());
    assertThrows(DomainException.class, () -> agentModelService.deleteModel(modelName, 0L));
    // 硬删除：同名立即可重建，重建后解析到新行。
    ModelDTO recreated = agentModelService.createModel(model(provider.getName(), modelName));
    assertEquals(0L, recreated.getVersion());
    agentModelService.deleteModel(modelName, recreated.getVersion());
    agentProviderService.deleteProvider(provider.getName(), 0L);
    agentProviderService.deleteProvider(otherProvider.getName(), 0L);
  }

  @Test
  public void shouldRenameModelAndAtomicallyUpdateAgentsGroupsAndCooldowns() {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "rename-provider-" + suffix;
    provider(providerName);

    String originalModel = "model-orig-" + suffix;
    String renamedModel = "model-renamed-" + suffix;
    String existingModel = "model-existing-" + suffix;

    ModelDTO origDto = agentModelService.createModel(model(providerName, originalModel));
    agentModelService.createModel(model(providerName, existingModel));
    ModelDTO group =
        agentModelService.createModel(group("group-" + suffix, originalModel, "default"));

    String agentName = "agent-ref-" + suffix;
    AgentDTO agentDto = agentService.createAgent(agent(originalModel, agentName));
    assertEquals(
        originalModel, agentDto.getExecution().getSettings().get("modelName").stringValue());

    insertCooldown(group.getName(), originalModel, "default", group.getVersion());

    SingleModelUpdateDTO renameReq = modelUpdate(origDto, renamedModel, "renamed desc");
    ModelDTO renamedDto = agentModelService.updateModel(originalModel, renameReq);
    assertEquals(renamedModel, renamedDto.getName());
    assertEquals(providerName, single(renamedDto).getProviderName());
    assertEquals(1L, renamedDto.getVersion());
    assertEquals(origDto.getCreatedAt(), renamedDto.getCreatedAt());

    assertThrows(
        DomainException.class, () -> agentModelService.updateModel(originalModel, renameReq));

    AgentDTO updatedAgent =
        agentService.listAgents().stream()
            .filter(candidate -> candidate.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertEquals(
        renamedModel, updatedAgent.getExecution().getSettings().get("modelName").stringValue());
    assertEquals(1L, updatedAgent.getVersion());

    ModelDTO renamedGroup =
        agentModelService.listModels().stream()
            .filter(candidate -> candidate.getName().equals(group.getName()))
            .findFirst()
            .orElseThrow();
    assertEquals(1L, renamedGroup.getVersion());
    assertEquals(renamedModel, groupOf(renamedGroup).getGroup().getMembers().get(0).getModelName());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from model_group_cooldown where group_name = ?",
            Integer.class,
            group.getName()));

    SingleModelUpdateDTO collisionReq = modelUpdate(renamedDto, existingModel, null);
    assertThrows(
        DomainException.class, () -> agentModelService.updateModel(renamedModel, collisionReq));
    ModelDTO unchanged =
        agentModelService.listModels().stream()
            .filter(candidate -> candidate.getName().equals(renamedModel))
            .findFirst()
            .orElseThrow();
    assertEquals(1L, unchanged.getVersion());
    assertEquals("renamed desc", unchanged.getDescription());

    // 组仍引用单 Model 时不可删除；删除组后引用消失。
    assertThrows(
        DomainException.class,
        () -> agentModelService.deleteModel(renamedModel, unchanged.getVersion()));
    agentModelService.deleteModel(renamedGroup.getName(), renamedGroup.getVersion());
    AgentDTO agentAfterCollision =
        agentService.listAgents().stream()
            .filter(candidate -> candidate.getName().equals(agentName))
            .findFirst()
            .orElseThrow();
    assertThrows(
        DomainException.class,
        () -> agentModelService.deleteModel(renamedModel, unchanged.getVersion()));
    agentService.deleteAgent(agentName, agentAfterCollision.getVersion());
    agentModelService.deleteModel(renamedModel, unchanged.getVersion());
    agentModelService.deleteModel(existingModel, 0L);
    agentProviderService.deleteProvider(providerName, 0L);
  }

  @Test
  public void shouldRejectRemovingVariantStillReferencedAndKeepGroupShapeStrict() {
    String suffix = Long.toString(System.nanoTime());
    String providerName = "variant-provider-" + suffix;
    provider(providerName);

    SingleModelCreateDTO create = model(providerName, "variant-model-" + suffix);
    create.setConfig(
        buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of("default", "fast"), "default"));
    ModelDTO model = agentModelService.createModel(create);

    ModelDTO group =
        agentModelService.createModel(group("variant-group-" + suffix, model.getName(), "fast"));
    assertEquals(ModelKind.GROUP, kindOf(group));
    assertTrue(group instanceof ModelGroupDTO);
    assertNull(groupOf(group).getConfig().getPricing());
    assertEquals(32768, group.getConfig().getLimit().getContext());
    assertEquals(4096, group.getConfig().getLimit().getOutput());
    assertEquals(List.of(), group.getConfig().getVariants());
    assertNull(group.getConfig().getDefaultVariant());
    assertEquals(1, groupOf(group).getGroup().getMembers().size());
    SingleModelUpdateDTO removeVariant = modelUpdate(model, model.getName(), null);
    removeVariant.setConfig(
        buildConfig(32768, 4096, true, false, "standard", "v1", List.of("default"), "default"));
    assertThrows(
        DomainException.class, () -> agentModelService.updateModel(model.getName(), removeVariant));

    ModelCreateDTO invalidGroup = group("invalid-group-" + suffix, model.getName(), "missing");
    assertThrows(DomainException.class, () -> agentModelService.createModel(invalidGroup));

    agentModelService.deleteModel(group.getName(), group.getVersion());
    agentModelService.deleteModel(model.getName(), model.getVersion());
    agentProviderService.deleteProvider(providerName, 0L);
  }

  /** 真实 PostgreSQL 主键决定全局名称竞争：并发 create 只允许一个成功。 */
  @Test
  public void shouldSerializeConcurrentCreatesOfTheSameGlobalName() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = provider("concurrent-create-provider-" + suffix);
    String name = "concurrent-create-model-" + suffix;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ModelDTO> first =
          executor.submit(
              () -> {
                start.await();
                return agentModelService.createModel(model(provider.getName(), name));
              });
      Future<ModelDTO> second =
          executor.submit(
              () -> {
                start.await();
                return agentModelService.createModel(model(provider.getName(), name));
              });
      start.countDown();
      List<Outcome<ModelDTO>> outcomes = List.of(outcome(first), outcome(second));
      assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count());
      assertEquals(1, outcomes.stream().filter(Outcome::duplicate).count());
      ModelDTO created =
          outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
      agentModelService.deleteModel(created.getName(), created.getVersion());
      agentProviderService.deleteProvider(provider.getName(), 0L);
    } finally {
      executor.shutdownNow();
    }
  }

  /** 两个来源并发 rename 到同一目标名：仍由 name 主键裁决，不允许覆盖已有行。 */
  @Test
  public void shouldSerializeConcurrentRenamesToTheSameName() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = provider("concurrent-rename-provider-" + suffix);
    ModelDTO firstSource =
        agentModelService.createModel(model(provider.getName(), "rename-source-a-" + suffix));
    ModelDTO secondSource =
        agentModelService.createModel(model(provider.getName(), "rename-source-b-" + suffix));
    String target = "rename-target-" + suffix;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ModelDTO> first =
          executor.submit(
              () -> {
                start.await();
                return agentModelService.updateModel(
                    firstSource.getName(), modelUpdate(firstSource, target, "a"));
              });
      Future<ModelDTO> second =
          executor.submit(
              () -> {
                start.await();
                return agentModelService.updateModel(
                    secondSource.getName(), modelUpdate(secondSource, target, "b"));
              });
      start.countDown();
      List<Outcome<ModelDTO>> outcomes = List.of(outcome(first), outcome(second));
      assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count());
      assertEquals(1, outcomes.stream().filter(Outcome::duplicate).count());
      ModelDTO renamed =
          outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
      ModelDTO untouched =
          agentModelService.listModels().stream()
              .filter(
                  candidate ->
                      candidate.getName().equals(firstSource.getName())
                          || candidate.getName().equals(secondSource.getName()))
              .findFirst()
              .orElseThrow();
      agentModelService.deleteModel(renamed.getName(), renamed.getVersion());
      agentModelService.deleteModel(untouched.getName(), untouched.getVersion());
      agentProviderService.deleteProvider(provider.getName(), 0L);
    } finally {
      executor.shutdownNow();
    }
  }

  /** 配置导入与手工 create 并发争用同一全局名称时，仍只允许一行落地。 */
  @Test
  public void shouldSerializeConcurrentCreateAndConfigImportOfTheSameName() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = provider("import-contention-provider-" + suffix);
    String modelName = "import-contention-model-" + suffix;
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml(
        "providers:\n"
            + "  - name: "
            + provider.getName()
            + "\n    providerType: openai\n"
            + "models:\n"
            + "  - kind: MODEL\n    providerName: "
            + provider.getName()
            + "\n    name: "
            + modelName
            + "\n    modelId: gpt\n"
            + "    config:\n"
            + "      limit: {context: 1000, output: 100}\n"
            + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
            + "      pricing: {base: {input: \"0\", output: \"0\"}}\n"
            + "      defaultVariant: default\n"
            + "      variants: [{id: default, promptCacheRetention: NONE}]\n");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> imported =
          executor.submit(
              () -> {
                start.await();
                return configSyncService.importYaml(request);
              });
      Future<ModelDTO> created =
          executor.submit(
              () -> {
                start.await();
                return agentModelService.createModel(model(provider.getName(), modelName));
              });
      start.countDown();
      Outcome<?> importOutcome = outcome(imported);
      Outcome<ModelDTO> createOutcome = outcome(created);
      // 导入是幂等 upsert：并发时可能落到 create 或 update；但 name 主键必须保证最终只有一行。
      assertTrue(importOutcome.succeeded() || importOutcome.duplicate());
      assertTrue(createOutcome.succeeded() || createOutcome.duplicate());
      assertEquals(
          1L,
          jdbc.queryForObject("select count(*) from model where name = ?", Long.class, modelName));
      ModelDTO persisted =
          agentModelService.listModels().stream()
              .filter(candidate -> candidate.getName().equals(modelName))
              .findFirst()
              .orElseThrow();
      agentModelService.deleteModel(persisted.getName(), persisted.getVersion());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 组更新先锁旧、新成员：旧成员仅被并发锁住时，更新也必须等待，才能安全替换成员集合。 */
  @Test
  public void shouldLockOldAndNewMembersBeforeUpdatingGroup() throws Exception {
    String suffix = Long.toString(System.nanoTime());
    ProviderDTO provider = provider("group-lock-provider-" + suffix);
    ModelDTO oldMember =
        agentModelService.createModel(model(provider.getName(), "group-old-" + suffix));
    ModelDTO newMember =
        agentModelService.createModel(model(provider.getName(), "group-new-" + suffix));
    ModelDTO group =
        agentModelService.createModel(
            group("group-lock-" + suffix, oldMember.getName(), "default"));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch memberLocked = new CountDownLatch(1);
    CountDownLatch releaseMemberLock = new CountDownLatch(1);
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "select name from model where name = ? for update",
                                String.class,
                                oldMember.getName());
                            memberLocked.countDown();
                            await(releaseMemberLock);
                          }));
      assertTrue(memberLocked.await(5, TimeUnit.SECONDS));
      ModelUpdateDTO update = groupUpdate(group, newMember.getName(), "default");
      Future<ModelDTO> updater =
          executor.submit(() -> agentModelService.updateModel(group.getName(), update));
      Thread.sleep(300);
      assertFalse(updater.isDone(), "group update must wait for the old member row lock");
      releaseMemberLock.countDown();
      holder.get(5, TimeUnit.SECONDS);
      ModelDTO updated = updater.get(5, TimeUnit.SECONDS);
      assertEquals(List.of(newMember.getName()), memberNames(updated));
      agentModelService.deleteModel(updated.getName(), updated.getVersion());
      agentModelService.deleteModel(oldMember.getName(), oldMember.getVersion());
      agentModelService.deleteModel(newMember.getName(), newMember.getVersion());
      agentProviderService.deleteProvider(provider.getName(), 0L);
    } finally {
      releaseMemberLock.countDown();
      executor.shutdownNow();
    }
  }

  private static List<String> memberNames(ModelDTO group) {
    return groupOf(group).getGroup().getMembers().stream()
        .map(ModelGroupMemberDTO::getModelName)
        .toList();
  }

  private static <T> Outcome<T> outcome(Future<T> future) throws InterruptedException {
    try {
      return new Outcome<>(future.get(5, TimeUnit.SECONDS), null);
    } catch (ExecutionException error) {
      return new Outcome<>(null, error.getCause());
    } catch (TimeoutException error) {
      throw new AssertionError("concurrent operation timed out", error);
    }
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

  private record Outcome<T>(T value, Throwable error) {
    private boolean succeeded() {
      return error == null;
    }

    private boolean duplicate() {
      return error instanceof DomainException domain && domain.code() == DomainErrorCode.DUPLICATE;
    }
  }

  private void insertCooldown(
      String groupName, String memberModelName, String variant, long groupVersion) {
    jdbc.update(
        "insert into model_group_cooldown"
            + " (group_name, member_model_name, member_variant, group_version, cooldown_until)"
            + " values (?, ?, ?, ?, current_timestamp + interval '1 hour')",
        groupName,
        memberModelName,
        variant,
        groupVersion);
  }

  private AgentCreateDTO agent(String model, String name) {
    AgentCreateDTO dto = new AgentCreateDTO();
    dto.setName(name);
    ObjectNode settings = jsonObject();
    settings.put("modelName", model);
    settings.putNull("variant");
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver(BuiltinExecution.DRIVER);
    execution.setSettings(settings);
    dto.setExecution(execution);
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    dto.setConfig(config);
    return dto;
  }

  private ProviderDTO provider(String name) {
    ProviderCreateDTO dto = new ProviderCreateDTO();
    dto.setName(name);
    dto.setProviderType(ProviderType.OPENAI);
    return agentProviderService.createProvider(dto);
  }

  private SingleModelCreateDTO model(String providerName, String name) {
    SingleModelCreateDTO dto = new SingleModelCreateDTO();
    dto.setProviderName(providerName);
    dto.setName(name);
    executable(dto);
    return dto;
  }

  private ModelGroupCreateDTO group(String name, String modelName, String variant) {
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant(variant);
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(member));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(30);
    ModelGroupCreateDTO dto = new ModelGroupCreateDTO();
    dto.setName(name);
    dto.setGroup(group);
    return dto;
  }

  private ModelGroupUpdateDTO groupUpdate(ModelDTO current, String memberName, String variant) {
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName(memberName);
    member.setVariant(variant);
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(member));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(30);
    ModelGroupUpdateDTO dto = new ModelGroupUpdateDTO();
    dto.setName(current.getName());
    dto.setGroup(group);
    dto.setExpectedVersion(current.getVersion());
    return dto;
  }

  private SingleModelUpdateDTO modelUpdate(ModelDTO current, String name, String description) {
    SingleModelUpdateDTO dto = new SingleModelUpdateDTO();
    dto.setName(name);
    dto.setProviderName(single(current).getProviderName());
    dto.setModelId(single(current).getModelId());
    dto.setDescription(description);
    dto.setConfig(current.getConfig());
    dto.setExpectedVersion(current.getVersion());
    return dto;
  }

  private static SingleModelDTO single(ModelDTO model) {
    return (SingleModelDTO) model;
  }

  private static ModelGroupDTO groupOf(ModelDTO model) {
    return (ModelGroupDTO) model;
  }

  private static ModelKind kindOf(ModelDTO model) {
    return model instanceof SingleModelDTO ? ModelKind.MODEL : ModelKind.GROUP;
  }
}
