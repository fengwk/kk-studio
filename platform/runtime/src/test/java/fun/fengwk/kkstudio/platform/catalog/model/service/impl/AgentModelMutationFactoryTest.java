package fun.fengwk.kkstudio.platform.catalog.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 模型变更拒绝 Harness runtime 无法执行的配置，并按全局名称解析组成员。 */
public class AgentModelMutationFactoryTest {

  private static AgentModelConfigDTO validConfig() {
    return AgentModelTestData.buildConfig(
        32768, 4096, true, false, "standard", "v1", List.of("default"), "default");
  }

  @Test
  public void shouldRequireExecutableModelConfigurationAndKeepIdentity() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setModelId("wire-model");

    assertThrows(AiValidationException.class, () -> factory.newModel("model", create));
    AgentModel existing = new AgentModel();
    existing.setName("existing");
    existing.setKind(AgentModelKind.MODEL);
    assertThrows(
        AiValidationException.class, () -> factory.update(existing, new AgentModelUpdateDTO()));

    create.setKind(AgentModelKind.MODEL);
    create.setName("model");
    create.setProviderName("provider");
    create.setConfig(validConfig());
    AgentModel model = factory.newModel("model", create);
    assertEquals("provider", model.getProviderName());
    assertEquals("model", model.getName());
    factory.update(model, create);
    assertEquals("model", model.getName());
    assertEquals("provider", model.getProviderName());

    AgentModelConfigDTO invalid =
        AgentModelTestData.buildConfig(
            32768, 4096, true, false, "standard", "v1", List.of(), "missing");
    create.setConfig(invalid);
    assertThrows(AiValidationException.class, () -> factory.newModel("model", create));
    assertThrows(
        AiValidationException.class,
        () -> {
          AgentModelCreateDTO blank = create("model", null);
          blank.setProviderName(" ");
          factory.newModel("model", blank);
        });
    assertThrows(AiValidationException.class, () -> factory.newModel("model", null));
  }

  @Test
  public void shouldPreservePricingDefinitionAcrossEdit() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setProviderName("provider");
    model.setName("model");
    model.setKind(AgentModelKind.MODEL);
    model.setModelId("wire-model");
    AgentModelConfigDTO baseline = validConfig();
    AgentModelRateSetDTO rates = new AgentModelRateSetDTO();
    rates.setInput(new BigDecimal("7"));
    rates.setOutput(new BigDecimal("9"));
    rates.setCacheRead(new BigDecimal("1"));
    rates.setCacheWrite(new BigDecimal("2"));
    rates.setCacheWriteByTtl(Map.of("300", new BigDecimal("3")));
    rates.setReasoning(new BigDecimal("4"));
    AgentModelPricingDTO pricing = baseline.getPricing();
    pricing.setBase(rates);

    AgentModelUpdateDTO update = new AgentModelUpdateDTO();
    update.setKind(AgentModelKind.MODEL);
    update.setProviderName("provider");
    update.setName(model.getName());
    update.setModelId(model.getModelId());
    update.setDescription("updated");
    update.setConfig(baseline);
    factory.update(model, update);

    AgentModelConfigDTO persisted =
        new AgentModelRuntimeConfigParser(JsonMapper.builder().build())
            .decode(model.getConfigJson());
    assertNotNull(persisted.getPricing());
    AgentModelRateSetDTO persistedRates = persisted.getPricing().getBase();
    assertEquals(new BigDecimal("7"), persistedRates.getInput());
    assertEquals(new BigDecimal("9"), persistedRates.getOutput());
    assertEquals(new BigDecimal("1"), persistedRates.getCacheRead());
    assertEquals(new BigDecimal("2"), persistedRates.getCacheWrite());
    assertEquals(new BigDecimal("3"), persistedRates.getCacheWriteByTtl().get("300"));
    assertEquals(new BigDecimal("4"), persistedRates.getReasoning());
  }

  // 测试意图: 断言 name/modelId 仍受 schema 列宽约束，而 description 已放开为 text 不再拒绝长文本
  @Test
  public void shouldEnforceModelSchemaStringLimits() {
    AgentModelMutationFactory factory = factory();
    AgentModelCreateDTO accepted = create("n".repeat(128), "d".repeat(512));
    AgentModel persisted = factory.newModel(accepted.getName(), accepted);
    assertEquals("n".repeat(128), persisted.getName());
    assertEquals("d".repeat(512), persisted.getDescription());

    AgentModelCreateDTO unbounded = create("model", "d".repeat(4096));
    assertEquals("d".repeat(4096), factory.newModel("model", unbounded).getDescription());

    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("\u2003model\u2003", create("model", null)));
    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("n".repeat(129), create("n".repeat(129), null)));
    AgentModelCreateDTO oversizedModelId = create("model", null);
    oversizedModelId.setModelId("m".repeat(257));
    assertThrows(AiValidationException.class, () -> factory.newModel("model", oversizedModelId));
  }

  @Test
  public void shouldSupportRenamingModelAndValidateTargetName() {
    AgentModelMutationFactory factory = factory();
    AgentModel model = new AgentModel();
    model.setProviderName("provider");
    model.setName("old-name");
    model.setKind(AgentModelKind.MODEL);
    model.setModelId("wire-model");
    model.setConfigJson(parser().encode(validConfig()));

    AgentModelUpdateDTO rename = new AgentModelUpdateDTO();
    rename.setKind(AgentModelKind.MODEL);
    rename.setProviderName("provider");
    rename.setName("new-name");
    rename.setModelId("wire-model");
    rename.setConfig(validConfig());
    factory.update(model, rename);
    assertEquals("new-name", model.getName());

    AgentModelUpdateDTO blankName = new AgentModelUpdateDTO();
    blankName.setKind(AgentModelKind.MODEL);
    blankName.setName("   ");
    blankName.setModelId("wire-model");
    blankName.setConfig(validConfig());
    assertThrows(AiValidationException.class, () -> factory.update(model, blankName));

    AgentModelUpdateDTO surroundingWhitespace = new AgentModelUpdateDTO();
    surroundingWhitespace.setKind(AgentModelKind.MODEL);
    surroundingWhitespace.setName(" padded-name ");
    surroundingWhitespace.setModelId("wire-model");
    surroundingWhitespace.setConfig(validConfig());
    assertThrows(AiValidationException.class, () -> factory.update(model, surroundingWhitespace));

    AgentModelUpdateDTO tooLong = new AgentModelUpdateDTO();
    tooLong.setKind(AgentModelKind.MODEL);
    tooLong.setName("n".repeat(129));
    tooLong.setModelId("wire-model");
    tooLong.setConfig(validConfig());
    assertThrows(AiValidationException.class, () -> factory.update(model, tooLong));
  }

  /** 测试意图：组只接受已存在单 Model 声明的 Variant，不能嵌套组，成员输入模态必须有交集。 */
  @Test
  public void shouldValidateGroupMembersAndDerivedModalities() {
    AgentModelRepository repository = mock(AgentModelRepository.class);
    AgentModelMutationFactory factory = factory(repository);
    AgentModel member = new AgentModel();
    member.setName("member");
    member.setKind(AgentModelKind.MODEL);
    member.setConfigJson(parser().encode(validConfig()));
    when(repository.getByNameForUpdate("member")).thenReturn(member);

    AgentModelCreateDTO group = groupCreate("group", member("member", "default"));
    AgentModel created = factory.newModel("group", group);
    assertEquals(AgentModelKind.GROUP, created.getKind());
    assertNotNull(created.getGroupConfigJson());

    when(repository.getByNameForUpdate("group")).thenReturn(created);
    assertThrows(
        AiValidationException.class,
        () -> factory.newModel("nested", groupCreate("nested", member("group", "default"))));
    assertThrows(
        AiValidationException.class,
        () ->
            factory.newModel(
                "missing-variant", groupCreate("missing-variant", member("member", "absent"))));

    AgentModel imageOnly = new AgentModel();
    imageOnly.setName("image-member");
    imageOnly.setKind(AgentModelKind.MODEL);
    AgentModelConfigDTO imageConfig = validConfig();
    imageConfig.getAbilities().setInputModalities(List.of(AgentModelInputModality.IMAGE));
    imageOnly.setConfigJson(parser().encode(imageConfig));
    when(repository.getByNameForUpdate("image-member")).thenReturn(imageOnly);
    AgentModelCreateDTO noIntersection =
        groupCreate(
            "no-intersection", member("member", "default"), member("image-member", "default"));
    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> factory.newModel("no-intersection", noIntersection));
    assertEquals("group.members have no common input modality", error.getMessage());
  }

  private static AgentModelCreateDTO create(String name, String description) {
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setKind(AgentModelKind.MODEL);
    create.setProviderName("provider");
    create.setModelId("wire-" + name);
    create.setDescription(description);
    create.setConfig(validConfig());
    return create;
  }

  private static AgentModelCreateDTO groupCreate(String name, AgentModelGroupMemberDTO... members) {
    AgentModelGroupDTO group = new AgentModelGroupDTO();
    group.setMembers(List.of(members));
    group.setFallbackStatusCodes(List.of(429));
    group.setCooldownSeconds(0);
    AgentModelCreateDTO create = new AgentModelCreateDTO();
    create.setName(name);
    create.setKind(AgentModelKind.GROUP);
    create.setGroup(group);
    return create;
  }

  private static AgentModelGroupMemberDTO member(String modelName, String variant) {
    AgentModelGroupMemberDTO member = new AgentModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant(variant);
    return member;
  }

  private static AgentModelRuntimeConfigParser parser() {
    return new AgentModelRuntimeConfigParser(JsonMapper.builder().build());
  }

  private AgentModelMutationFactory factory() {
    return factory(mock(AgentModelRepository.class));
  }

  private AgentModelMutationFactory factory(AgentModelRepository repository) {
    ObjectMapper objectMapper = JsonMapper.builder().build();
    return new AgentModelMutationFactory(
        new AgentEditableSupport(objectMapper),
        new AgentModelRuntimeConfigParser(objectMapper),
        new AgentModelGroupConfigCodec(objectMapper),
        repository);
  }
}
