package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageEditDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackagePublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingPublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Platform 技能包 Web REST API 的 HTTP 契约测试。
 *
 * <p>验证 {@code /api/platform/skill-packages} 下的列表、详情、Git 创建/编辑/检查/发布、归档下载、各环境同步状态与环境重新同步：创建发布当时解析出的
 * branch HEAD；检查只更新观察值；发布只接受该 version 展示过的 exact commit；陈旧卡片、重复名与 Agent 引用保护映射到稳定的 HTTP 状态码。
 */
@AutoConfigureMockMvc
@Import(WebStorageS3TestConfiguration.class)
class SkillCatalogControllerTest extends WebPostgresTestSupport {

  private static final String DEV_SKILL_MD =
      """
      ---
      name: dev
      description: 开发者技能包含开发规范
      ---
      # dev content
      """;

  private static final String REVIEW_SKILL_MD =
      """
      ---
      name: review
      description: 评审技能
      ---
      # review content
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /** 测试意图：完整生命周期——创建发布 branch HEAD、检查只更新观察值、发布 exact observed commit、CAS 删除。 */
  @Test
  void fullLifecyclePublishesExactObservedCommit() throws Exception {
    GitFixture git = GitFixture.init("lifecycle", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("lifecycle");
    String firstCommit = git.firstCommit();

    MvcResult createResult =
        mockMvc
            .perform(createRequest(name, git.url()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.packageName").value(name))
            .andExpect(jsonPath("$.packageVersion").value(firstCommit))
            .andExpect(jsonPath("$.source").value("GIT"))
            .andExpect(jsonPath("$.git.repositoryUrl").value(git.url()))
            .andExpect(jsonPath("$.git.branch").value("main"))
            .andExpect(jsonPath("$.git.token.configured").value(false))
            .andExpect(jsonPath("$.headCommit").value(firstCommit))
            .andExpect(jsonPath("$.headCheckError").value(nullValue()))
            .andExpect(jsonPath("$.updateAvailable").value(false))
            .andExpect(jsonPath("$.skills[0].name").value("dev"))
            .andExpect(jsonPath("$.skills[0].description").value("开发者技能包含开发规范"))
            .andExpect(jsonPath("$.version").value("0"))
            .andReturn();
    assertEquals(firstCommit, data(createResult).path("packageVersion").asText());

    // 列表与单读回读同一条权威事实。
    assertTrue(packageNames().contains(name), "created package must appear in the list");
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packageVersion").value(firstCommit))
        .andExpect(jsonPath("$.version").value("0"));

    // HEAD 未变时检查是只读操作，不推进 version。
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headCommit").value(firstCommit))
        .andExpect(jsonPath("$.version").value("0"));

    // 编辑：只有可编辑字段变化，version 前进。
    SkillPackageEditDTO edit = new SkillPackageEditDTO();
    edit.setExpectedVersion("0");
    edit.setDescription("updated description");
    edit.setBranch("release");
    mockMvc
        .perform(
            put("/api/platform/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(edit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("updated description"))
        .andExpect(jsonPath("$.git.branch").value("release"))
        .andExpect(jsonPath("$.packageVersion").value(firstCommit))
        .andExpect(jsonPath("$.version").value("1"));

    // branch 前进：检查只更新观察值，当前版本与清单保持原样。
    String secondCommit =
        git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD, "review/SKILL.md", REVIEW_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headCommit").value(secondCommit))
        .andExpect(jsonPath("$.packageVersion").value(firstCommit))
        .andExpect(jsonPath("$.updateAvailable").value(true))
        .andExpect(jsonPath("$.skills.length()").value(1))
        .andExpect(jsonPath("$.version").value("1"));

    // 发布卡片展示过的 exact commit：版本号与清单在同一次 CAS 中切换。
    mockMvc
        .perform(publishRequest(name, "1", secondCommit))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packageVersion").value(secondCommit))
        .andExpect(jsonPath("$.headCommit").value(secondCommit))
        .andExpect(jsonPath("$.updateAvailable").value(false))
        .andExpect(jsonPath("$.skills.length()").value(2))
        .andExpect(jsonPath("$.version").value("2"));

    // 文件清单只来自服务端归档索引，按路径升序返回 path 与解压后大小。
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}/files", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].path").value("dev/SKILL.md"))
        .andExpect(jsonPath("$[0].size").isString())
        .andExpect(jsonPath("$[1].path").value("review/SKILL.md"));

    // 归档下载返回预签名 GET 与摘要。
    mockMvc
        .perform(post("/api/platform/skill-packages/{name}/archive-url", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.filename").value(name + "-" + secondCommit + ".zip"))
        .andExpect(jsonPath("$.sha256").isNotEmpty())
        .andExpect(jsonPath("$.download.url").isNotEmpty());

    // 环境同步状态按环境投影，新建包尚未安装。
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}/environments", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    // 陈旧卡片的删除必须冲突，命中当前 version 才删除。
    mockMvc
        .perform(delete("/api/platform/skill-packages/{name}", name).param("expectedVersion", "1"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(delete("/api/platform/skill-packages/{name}", name).param("expectedVersion", "2"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}", name))
        .andExpect(status().isNotFound());
  }

  /** 测试意图：PUT 整体替换可编辑属性——Git 渠道必须给出检查分支，只改描述也推进 CAS version。 */
  @Test
  void unifiedEditReplacesEditableGitProperties() throws Exception {
    GitFixture git = GitFixture.init("edit-desc", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("edit-desc");
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());

    SkillPackageEditDTO edit = new SkillPackageEditDTO();
    edit.setExpectedVersion("0");
    edit.setDescription("description only");
    edit.setBranch("main");
    mockMvc
        .perform(
            put("/api/platform/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(edit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("description only"))
        .andExpect(jsonPath("$.git.branch").value("main"))
        .andExpect(jsonPath("$.version").value("1"));

    // Git 渠道省略 branch 不再是「保持当前值」：整体替换缺必填属性时拒绝
    SkillPackageEditDTO missingBranch = new SkillPackageEditDTO();
    missingBranch.setExpectedVersion("1");
    missingBranch.setDescription("description only");
    mockMvc
        .perform(
            put("/api/platform/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(missingBranch)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    mockMvc
        .perform(get("/api/platform/skill-packages/{name}/files", packageName("missing")))
        .andExpect(status().isNotFound());
  }

  /** 测试意图：重复 package 名、非法 repository URL 与无法解析的 branch 都被拒绝且不留下半成品行。 */
  @Test
  void rejectsDuplicateAndUnusableCreate() throws Exception {
    GitFixture git = GitFixture.init("create-guard", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("create-guard");

    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isConflict());
    mockMvc
        .perform(createRequest(packageName("bad-url"), "not-a-url"))
        .andExpect(status().isBadRequest());
    SkillPackageCreateDTO badBranch = create(packageName("bad-branch"), git.url());
    badBranch.setBranch("no-such-branch");
    mockMvc
        .perform(
            post("/api/platform/skill-packages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badBranch)))
        .andExpect(status().isBadRequest());

    assertEquals(List.of(name), packageNames(), "failed creates must not leave rows behind");
  }

  /** 测试意图：未知 Package、陈旧 version 与未被观察过的 commit 分别映射到 404 / 409 / 400。 */
  @Test
  void rejectsUnknownStaleAndUnobservedRequests() throws Exception {
    GitFixture git = GitFixture.init("guard", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("guard");
    String firstCommit = git.firstCommit();
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/platform/skill-packages/{name}", packageName("missing")))
        .andExpect(status().isNotFound());
    mockMvc.perform(checkRequest(packageName("missing"), "0")).andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/platform/skill-packages/{name}", packageName("missing"))
                .param("expectedVersion", "0"))
        .andExpect(status().isNotFound());

    SkillPackageEditDTO stale = new SkillPackageEditDTO();
    stale.setExpectedVersion("7");
    stale.setDescription("stale edit");
    stale.setBranch("main");
    mockMvc
        .perform(
            put("/api/platform/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isConflict());

    // 卡片观察到某 commit 之后 branch 又前进：该 version 只认当时展示的 exact commit。
    String observed = git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headCommit").value(observed))
        .andExpect(jsonPath("$.version").value("0"));
    String advanced =
        git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD, "review/SKILL.md", REVIEW_SKILL_MD));

    mockMvc.perform(publishRequest(name, "0", firstCommit)).andExpect(status().isBadRequest());
    mockMvc.perform(publishRequest(name, "0", advanced)).andExpect(status().isBadRequest());
    mockMvc
        .perform(publishRequest(name, "0", observed))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packageVersion").value(observed))
        .andExpect(jsonPath("$.version").value("1"));
  }

  /** 测试意图：仍被 Agent SkillRef 引用的 Package 不能删除，引用保护返回 409 且行保持原状。 */
  @Test
  void rejectsDeleteWhileAgentStillReferencesSkill() throws Exception {
    GitFixture git = GitFixture.init("in-use", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("in-use");
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    referencingAgent(name, "dev");

    mockMvc
        .perform(delete("/api/platform/skill-packages/{name}", name).param("expectedVersion", "0"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("0"))
        .andExpect(jsonPath("$.packageVersion").value(git.firstCommit()));
  }

  /** 测试意图：Skill 在清单中消失但仍被 Agent 引用时，发布也必须拒绝。 */
  @Test
  void rejectsPublishRemovingReferencedSkill() throws Exception {
    GitFixture git = GitFixture.init("publish-in-use", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("publish-in-use");
    String firstCommit = git.firstCommit();
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    referencingAgent(name, "dev");

    String removingCommit = git.commit(Map.of("review/SKILL.md", REVIEW_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headCommit").value(removingCommit));
    mockMvc.perform(publishRequest(name, "0", removingCommit)).andExpect(status().isConflict());
    mockMvc
        .perform(get("/api/platform/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packageVersion").value(firstCommit))
        .andExpect(jsonPath("$.skills[0].name").value("dev"))
        .andExpect(jsonPath("$.version").value("0"));
  }

  /** 测试意图：不存在环境的重新同步请求返回 404，而不是静默成功。 */
  @Test
  void resyncUnknownEnvironmentIsNotFound() throws Exception {
    mockMvc
        .perform(
            post(
                "/api/platform/environments/{environmentId}/resync-skills",
                "00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isNotFound());
  }

  /** 测试意图：暂存归档是独立资源——id 在路径上以 UUID 解析，未知暂存 404、非 UUID 路径 400。 */
  @Test
  void stagingRoutesAddressTheStagedArchiveById() throws Exception {
    SkillPackageStagingCreateDTO staging = new SkillPackageStagingCreateDTO();
    staging.setUploadId(UUID.randomUUID().toString());
    staging.setPackageName("web-staged-pkg");
    staging.setPackageVersion("v1");
    mockMvc
        .perform(json(post("/api/platform/skill-package-stagings"), staging))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));

    SkillPackageStagingPublishDTO publish = new SkillPackageStagingPublishDTO();
    publish.setPackageName("web-staged-pkg");
    publish.setPackageVersion("v1");
    mockMvc
        .perform(
            json(
                post("/api/platform/skill-package-stagings/{stagingId}/publish", UUID.randomUUID()),
                publish))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));

    mockMvc
        .perform(
            json(
                post("/api/platform/skill-package-stagings/{stagingId}/publish", "not-a-uuid"),
                publish))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    mockMvc
        .perform(delete("/api/platform/skill-package-stagings/{stagingId}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
    mockMvc
        .perform(delete("/api/platform/skill-package-stagings/{stagingId}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  /** 通过公开 API 建立真实 Skill 引用：Agent config 精确指向某 Package 的 Skill。 */
  private void referencingAgent(String packageName, String skillName) throws Exception {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef(packageName, skillName)));
    config.setSubagents(List.of());
    AgentCreateDTO agent = new AgentCreateDTO();
    agent.setName("web-agent-" + System.nanoTime());
    agent.setDescription("skill reference guard agent");
    agent.setSystemPrompt("guard");
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver("builtin");
    execution.setSettings(Map.of("modelName", "acceptance-stub", "variant", "default"));
    agent.setExecution(execution);
    agent.setConfig(config);
    mockMvc
        .perform(
            post("/api/platform/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agent)))
        .andExpect(status().isCreated());
  }

  private MockHttpServletRequestBuilder createRequest(String packageName, String repositoryUrl)
      throws Exception {
    return json(post("/api/platform/skill-packages"), create(packageName, repositoryUrl));
  }

  private MockHttpServletRequestBuilder checkRequest(String packageName, String expectedVersion)
      throws Exception {
    SkillPackageCheckDTO check = new SkillPackageCheckDTO();
    check.setExpectedVersion(expectedVersion);
    return json(post("/api/platform/skill-packages/{name}/check", packageName), check);
  }

  private MockHttpServletRequestBuilder publishRequest(
      String packageName, String expectedVersion, String targetCommit) throws Exception {
    SkillPackagePublishDTO publish = new SkillPackagePublishDTO();
    publish.setExpectedVersion(expectedVersion);
    publish.setTargetCommit(targetCommit);
    return json(post("/api/platform/skill-packages/{name}/publish", packageName), publish);
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body)
      throws Exception {
    return builder
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private static SkillPackageCreateDTO create(String packageName, String repositoryUrl) {
    SkillPackageCreateDTO create = new SkillPackageCreateDTO();
    create.setPackageName(packageName);
    create.setDescription("web skill package");
    create.setRepositoryUrl(repositoryUrl);
    create.setBranch("main");
    return create;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private static String packageName(String prefix) {
    return "web-" + prefix + "-" + System.nanoTime();
  }

  private List<String> packageNames() throws Exception {
    JsonNode list =
        data(
            mockMvc
                .perform(get("/api/platform/skill-packages"))
                .andExpect(status().isOk())
                .andReturn());
    List<String> names = new ArrayList<>();
    list.forEach(node -> names.add(node.path("packageName").asText()));
    return names;
  }

  private JsonNode data(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(body);
  }

  /** 测试临时目录中的技能仓库：每个测试独立，{@code main} 与 {@code release} 同步推进。 */
  private static final class GitFixture {

    private final Path directory;
    private final Git git;
    private final String firstCommit;

    private GitFixture(Path directory, Git git, String firstCommit) {
      this.directory = directory;
      this.git = git;
      this.firstCommit = firstCommit;
    }

    private static GitFixture init(String prefix, Map<String, String> files) throws Exception {
      Path directory = Files.createTempDirectory("kk-studio-skill-repo-" + prefix);
      Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call();
      String firstCommit = commit(git, directory, files);
      return new GitFixture(directory, git, firstCommit);
    }

    /** 写入给定文件集合并提交一次，让 {@code main} 与 {@code release} 都指向新 commit，返回 commit id。 */
    private String commit(Map<String, String> files) throws Exception {
      return commit(git, directory, files);
    }

    /** 每次提交都声明完整 tree：先清空工作区（{@code .git} 除外）再写入给定文件集合。 */
    private static String commit(Git git, Path directory, Map<String, String> files)
        throws Exception {
      clearTree(directory);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path file = directory.resolve(entry.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
      }
      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("commit").setSign(false).call();
      try (Repository repository = git.getRepository()) {
        for (String ref : List.of("refs/heads/main", "refs/heads/release")) {
          RefUpdate update = repository.updateRef(ref);
          update.setNewObjectId(commit.getId());
          update.setForceUpdate(true);
          update.update();
        }
      }
      return commit.getId().getName();
    }

    private static void clearTree(Path directory) throws IOException {
      Path gitDirectory = directory.resolve(".git");
      try (Stream<Path> walk = Files.walk(directory)) {
        for (Path path : walk.filter(Files::isRegularFile).toList()) {
          if (!path.startsWith(gitDirectory)) {
            Files.delete(path);
          }
        }
      }
    }

    private String firstCommit() {
      return firstCommit;
    }

    private String url() {
      return directory.toUri().toString();
    }
  }
}
