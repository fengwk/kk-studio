package fun.fengwk.kkstudio.share.ai.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

/** Skill Package wire 契约：严格请求字段、十进制 version、required-nullable 检查事实与正文缺席。 */
class SkillPackageDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void createRequestCarriesOnlyRepositoryFacts() throws Exception {
    // 意图：create 只接收 packageName/description/repositoryUrl/branch/token；客户端提交 commit 就被拒绝。
    SkillPackageCreateDTO dto =
        MAPPER.readValue(
            """
            {"packageName":"dev-tools","description":null,
             "repositoryUrl":"https://example.com/acme/skills.git","branch":"main",
             "token":"ghp_example"}
            """,
            SkillPackageCreateDTO.class);
    assertEquals("dev-tools", dto.getPackageName());
    assertNull(dto.getDescription());
    assertEquals("https://example.com/acme/skills.git", dto.getRepositoryUrl());
    assertEquals("main", dto.getBranch());
    assertEquals("ghp_example", dto.getToken());

    // 令牌省略时保持 null，表示匿名访问。
    SkillPackageCreateDTO anonymous =
        MAPPER.readValue(
            """
            {"packageName":"dev-tools","repositoryUrl":"https://example.com/acme/skills.git",
             "branch":"main"}
            """,
            SkillPackageCreateDTO.class);
    assertNull(anonymous.getToken());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"packageName":"dev-tools","repositoryUrl":"https://example.com/acme/skills.git",
                 "branch":"main","currentCommit":"0123456789012345678901234567890123456789"}
                """,
                SkillPackageCreateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"packageName":"dev-tools","repositoryUrl":"https://example.com/acme/skills.git",
                 "branch":"main","skills":[]}
                """,
                SkillPackageCreateDTO.class));
  }

  @Test
  void editCheckAndPublishRequestsAreStrictlyDeclared() throws Exception {
    // 意图：三个 CAS 请求精确为 {expectedVersion, description, branch, token?}、{expectedVersion}、
    // {expectedVersion, targetCommit}；编辑 token 是省略保留 / null 清除 / 非空替换的三态。
    SkillPackageEditDTO edit =
        MAPPER.readValue(
            "{\"expectedVersion\":\"3\",\"description\":\"d\",\"branch\":\"trunk\",\"token\":\"ghp_new\"}",
            SkillPackageEditDTO.class);
    assertEquals("3", edit.getExpectedVersion());
    assertEquals("trunk", edit.getBranch());
    assertTrue(edit.isTokenProvided());
    assertEquals("ghp_new", edit.getToken());

    // 省略 token：保留既有令牌。
    SkillPackageEditDTO preserve =
        MAPPER.readValue(
            "{\"expectedVersion\":\"3\",\"description\":\"d\",\"branch\":\"trunk\"}",
            SkillPackageEditDTO.class);
    assertFalse(preserve.isTokenProvided());
    assertNull(preserve.getToken());

    // 显式 null：清除令牌。
    SkillPackageEditDTO clear =
        MAPPER.readValue(
            "{\"expectedVersion\":\"3\",\"description\":\"d\",\"branch\":\"trunk\",\"token\":null}",
            SkillPackageEditDTO.class);
    assertTrue(clear.isTokenProvided());
    assertNull(clear.getToken());

    SkillPackageCheckDTO check =
        MAPPER.readValue("{\"expectedVersion\":\"3\"}", SkillPackageCheckDTO.class);
    assertEquals("3", check.getExpectedVersion());

    SkillPackagePublishDTO publish =
        MAPPER.readValue(
            "{\"expectedVersion\":\"3\",\"targetCommit\":\"0123456789012345678901234567890123456789\"}",
            SkillPackagePublishDTO.class);
    assertEquals("0123456789012345678901234567890123456789", publish.getTargetCommit());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"3\",\"commit\":\"abc\"}", SkillPackageEditDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"3\",\"extra\":1}", SkillPackageCheckDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedVersion\":\"3\",\"targetCommit\":\"abc\",\"branch\":\"main\"}",
                SkillPackagePublishDTO.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillPackageCheckDTO().rejectUnknownField("extra", 1));
  }

  @Test
  void packageProjectionExposesDecimalVersionAndNullableCheckFacts() throws Exception {
    // 意图：version 在 wire 上必须是十进制字符串，检查观察三元组即使为 null 也必须显式发射。
    Field version = SkillPackageDTO.class.getDeclaredField("version");
    assertEquals(String.class, version.getType());
    for (String fieldName : List.of("observedHeadCommit", "headCheckedAt", "headCheckError")) {
      JsonInclude include =
          SkillPackageDTO.class.getDeclaredField(fieldName).getAnnotation(JsonInclude.class);
      assertNotNull(include, fieldName + " must declare JsonInclude");
      assertEquals(JsonInclude.Include.ALWAYS, include.value(), fieldName);
    }

    SkillPackageDTO dto = new SkillPackageDTO();
    dto.setPackageName("dev-tools");
    dto.setVersion("7");
    dto.setSkills(List.of());
    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"version\":\"7\""), json);
    assertTrue(json.contains("\"observedHeadCommit\":null"), json);
    assertTrue(json.contains("\"headCheckError\":null"), json);
  }

  @Test
  void packageProjectionCarriesNoSkillContentOrPackageVersion() {
    // 意图：旧 wire 的正文与 package 版本被彻底删除，manifest 元素只有 name/description。
    assertThrows(
        NoSuchFieldException.class, () -> SkillPackageDTO.class.getDeclaredField("packageVersion"));
    assertThrows(
        NoSuchFieldException.class, () -> SkillPackageDTO.class.getDeclaredField("content"));
    assertThrows(
        NoSuchFieldException.class, () -> SkillPackageDTO.class.getDeclaredField("active"));
    assertThrows(
        NoSuchFieldException.class, () -> SkillManifestEntryDTO.class.getDeclaredField("content"));
    assertThrows(
        NoSuchFieldException.class, () -> SkillRefDTO.class.getDeclaredField("packageVersion"));
  }

  @Test
  void skillRefIsAStrictIdentityPair() throws Exception {
    // 意图：Agent 配置里的 Skill 引用精确为 {packageName, name}，任何多余字段 fail closed。
    SkillRefDTO ref =
        MAPPER.readValue("{\"packageName\":\"dev-tools\",\"name\":\"dev\"}", SkillRefDTO.class);
    assertEquals("dev-tools", ref.getPackageName());
    assertEquals("dev", ref.getName());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"packageName\":\"dev-tools\",\"name\":\"dev\",\"content\":\"# dev\"}",
                SkillRefDTO.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillRefDTO().rejectUnknownField("content", "# dev"));
  }

  @Test
  void deletedSkillPackageDtosAreGone() {
    // 意图：clean-slate 删除的旧 DTO 不得回归（无别名、无兼容构造器）。
    String packagePrefix = SkillRefDTO.class.getPackageName() + ".";
    for (String simpleName :
        List.of(
            "SkillDTO", "SkillDefinitionDTO", "SkillPackageDetailDTO", "SkillPackageUpdateDTO")) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(packagePrefix + simpleName),
          simpleName);
    }
  }
}
