package fun.fengwk.kkstudio.platform.catalog.skill.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.harness.skill.DatabaseSkillContentLoader;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUpdateDTO;

import java.util.List;

/** Platform 全局 Skill package 生命周期、不可变 revision 与 Agent 引用保护集成测试。 */
public class SkillCatalogServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private SkillCatalogService service;
  @Autowired private SkillCatalogRepository repository;
  @Autowired private DatabaseSkillContentLoader contentLoader;
  @Autowired private JdbcTemplate jdbc;

  /** 意图：完整替换只切换当前目录，旧 revision 始终可按冻结身份精确读取。 */
  @Test
  public void preservesImmutableRevisionsAcrossUpdateAndDelete() {
    SkillPackageDetailDTO created =
        service.createPackage(
            create(
                "core",
                "1.0.0",
                "Core skills",
                skill("dev", "Development", "old body"),
                skill("whitespace", "Whitespace", "   ")));

    assertEquals("core", created.getName());
    assertEquals("1.0.0", created.getPackageVersion());
    assertEquals(List.of("dev", "whitespace"), skillNames(created));
    assertEquals(
        List.of("dev", "whitespace"), service.listSkills().stream().map(s -> s.getName()).toList());
    assertEquals(List.of("core"), service.listPackages().stream().map(p -> p.getName()).toList());
    assertEquals("1.0.0", service.getPackage("core").getPackageVersion());
    assertThrows(AiResourceNotFoundException.class, () -> service.getPackage("missing"));
    assertThrows(AiValidationException.class, () -> service.createPackage(null));
    assertThrows(
        AiDuplicateException.class,
        () ->
            service.createPackage(create("core", "other", null, skill("extra", "Extra", "body"))));

    SkillRevision oldRevision = repository.getRevision("core", "1.0.0", "dev");
    assertNotNull(oldRevision);
    assertEquals(
        "old body", contentLoader.load("core", "1.0.0", "dev", oldRevision.getContentRevision()));
    assertThrows(
        IllegalArgumentException.class,
        () -> contentLoader.load("core", "1.0.0", "dev", "0".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () -> contentLoader.load("core", "1.0.0", "missing", "0".repeat(64)));

    SkillPackageDetailDTO updated =
        service.updatePackage(
            "core",
            update(
                "1.0.0",
                "2.0.0",
                "Core skills v2",
                skill("dev", "Development v2", "new body"),
                skill("ops", "Operations", "ops body")));

    assertEquals("2.0.0", updated.getPackageVersion());
    assertEquals(List.of("dev", "ops"), skillNames(updated));
    assertFalse(repository.getPackage("core", "1.0.0").isActive());
    assertTrue(repository.getPackage("core", "2.0.0").isActive());
    assertEquals(
        "old body", contentLoader.load("core", "1.0.0", "dev", oldRevision.getContentRevision()));
    assertThrows(
        AiValidationException.class,
        () ->
            service.updatePackage(
                "core",
                update("2.0.0", "2.0.0", null, skill("dev", "Development v2", "new body"))));
    assertThrows(AiValidationException.class, () -> service.updatePackage("core", null));
    assertThrows(
        AiDuplicateException.class,
        () ->
            service.updatePackage(
                "core",
                update("2.0.0", "1.0.0", null, skill("dev", "Development v2", "new body"))));
    assertThrows(AiVersionConflictException.class, () -> service.deletePackage("core", "stale"));

    service.deletePackage("core", "2.0.0");

    assertTrue(service.listSkills().isEmpty());
    assertTrue(service.listPackages().isEmpty());
    assertFalse(repository.getPackage("core", "2.0.0").isActive());
    assertEquals("old body", repository.getRevision("core", "1.0.0", "dev").getContent());
    assertEquals("new body", repository.getRevision("core", "2.0.0", "dev").getContent());
    assertThrows(AiResourceNotFoundException.class, () -> service.deletePackage("core", "2.0.0"));
  }

  /** 意图：移除仍被 Agent 引用的名称必须失败，而保留同名并更新内容不算移除。 */
  @Test
  public void protectsAgentReferencesBySkillName() {
    service.createPackage(
        create(
            "core",
            "1",
            null,
            skill("dev", "Development", "body"),
            skill("ops", "Operations", "ops")));
    insertAgentReferencing("dev");

    assertThrows(
        AiInUseException.class,
        () ->
            service.updatePackage(
                "core", update("1", "2", null, skill("ops", "Operations", "ops v2"))));
    assertThrows(AiInUseException.class, () -> service.deletePackage("core", "1"));

    SkillPackageDetailDTO updated =
        service.updatePackage(
            "core",
            update(
                "1",
                "2",
                null,
                skill("dev", "Development", "body v2"),
                skill("ops", "Operations", "ops v2")));
    assertEquals("2", updated.getPackageVersion());
    assertEquals("body v2", repository.getRevision("core", "2", "dev").getContent());
  }

  /** 意图：版本永不复用、Skill 名全局唯一，失败安装不得遗留 package 或目录行。 */
  @Test
  public void rejectsVersionReuseAndCrossPackageSkillConflictsAtomically() {
    service.createPackage(create("core", "1", null, skill("dev", "Development", "core body")));

    assertThrows(
        AiDuplicateException.class,
        () ->
            service.createPackage(
                create("other", "1", null, skill("dev", "Development", "other body"))));
    assertFalse(repository.existsPackage("other", "1"));

    assertThrows(
        AiVersionConflictException.class,
        () ->
            service.updatePackage(
                "core", update("stale", "2", null, skill("dev", "Development", "v2"))));

    service.deletePackage("core", "1");
    assertThrows(
        AiDuplicateException.class,
        () ->
            service.createPackage(
                create("core", "1", null, skill("dev", "Development", "reused"))));

    SkillPackageDetailDTO replacement =
        service.createPackage(
            create("core", "2", null, skill("dev", "Development", "replacement")));
    assertEquals("2", replacement.getPackageVersion());
    SkillPackage oldPackage = repository.getPackage("core", "1");
    assertNotNull(oldPackage);
    assertFalse(oldPackage.isActive());
  }

  private void insertAgentReferencing(String skillName) {
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values ('skill_test_provider', 'openai', '{}'::jsonb, "
            + "'00000000-0000-0000-0000-000000000101'::uuid)");
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values ('skill_test_provider', 'model', 'model', '{}'::jsonb)");
    jdbc.update(
        "insert into agent_definition "
            + "(name, model_provider_name, model_name, config) values "
            + "('skill_test_agent', 'skill_test_provider', 'model', "
            + "cast(? as jsonb))",
        "{\"tools\":[],\"skills\":[\"" + skillName + "\"],\"subagents\":[]}");
  }

  private static SkillPackageCreateDTO create(
      String name, String version, String description, SkillDefinitionDTO... skills) {
    SkillPackageCreateDTO dto = new SkillPackageCreateDTO();
    dto.setName(name);
    dto.setPackageVersion(version);
    dto.setDescription(description);
    dto.setSkills(List.of(skills));
    return dto;
  }

  private static SkillPackageUpdateDTO update(
      String expectedVersion, String newVersion, String description, SkillDefinitionDTO... skills) {
    SkillPackageUpdateDTO dto = new SkillPackageUpdateDTO();
    dto.setExpectedPackageVersion(expectedVersion);
    dto.setNewPackageVersion(newVersion);
    dto.setDescription(description);
    dto.setSkills(List.of(skills));
    return dto;
  }

  private static SkillDefinitionDTO skill(String name, String description, String content) {
    SkillDefinitionDTO dto = new SkillDefinitionDTO();
    dto.setName(name);
    dto.setDescription(description);
    dto.setContent(content);
    return dto;
  }

  private static List<String> skillNames(SkillPackageDetailDTO dto) {
    return dto.getSkills().stream().map(SkillDefinitionDTO::getName).toList();
  }
}
