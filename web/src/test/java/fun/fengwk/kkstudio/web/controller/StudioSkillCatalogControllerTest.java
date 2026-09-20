package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Platform 全局 Git Skill Package Web REST API 的 HTTP 契约测试。
 *
 * <p>验证 {@code /api/ai/catalog/skill-packages} 下 list/get/create/edit/check/update/delete
 * 的端到端契约：Create 发布当时解析出的 branch HEAD；Check 只更新观察值、失败时保留已发布内容；Update 只接受该 version 展示过的 exact
 * commit；陈旧 Card、重复名与 Agent 引用保护映射到稳定的 HTTP 状态码。技能仓库与 Platform bare cache 都落在进程临时目录，测试不接触模块工作树。
 */
@AutoConfigureMockMvc
class StudioSkillCatalogControllerTest extends WebPostgresTestSupport {

  /** 技能的 Git 仓库与 Platform bare cache 都落在进程临时目录。 */
  private static final Path TEST_ROOT = createTestRoot();

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

  @DynamicPropertySource
  static void overrideSkillCacheRoot(DynamicPropertyRegistry registry) {
    // bare cache 根必须在测试临时目录，避免把 cache 写进模块目录。
    registry.add(
        "kk-studio.harness.runtime.skill-cache-root", () -> TEST_ROOT.resolve("cache").toString());
  }

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
            .andExpect(jsonPath("$.data.packageName").value(name))
            .andExpect(jsonPath("$.data.repositoryUrl").value(git.url()))
            .andExpect(jsonPath("$.data.branch").value("main"))
            .andExpect(jsonPath("$.data.currentCommit").value(firstCommit))
            .andExpect(jsonPath("$.data.observedHeadCommit").value(firstCommit))
            .andExpect(jsonPath("$.data.checkStatus").value("UP_TO_DATE"))
            .andExpect(jsonPath("$.data.headCheckError").doesNotExist())
            .andExpect(jsonPath("$.data.skills[0].name").value("dev"))
            .andExpect(jsonPath("$.data.skills[0].description").value("开发者技能包含开发规范"))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andReturn();
    assertEquals(firstCommit, data(createResult).path("currentCommit").asText());

    // 列表与单读回读同一条权威事实。
    assertTrue(packageNames().contains(name), "created package must appear in the list");
    mockMvc
        .perform(get("/api/ai/catalog/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentCommit").value(firstCommit))
        .andExpect(jsonPath("$.data.version").value("0"));

    // 检查：HEAD 未变时是只读操作，不推进 version。
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.checkStatus").value("UP_TO_DATE"))
        .andExpect(jsonPath("$.data.version").value("0"));

    // 编辑：repository URL 与已发布内容不可变，只有可编辑字段与 version 前进。
    SkillPackageEditDTO edit = new SkillPackageEditDTO();
    edit.setExpectedVersion("0");
    edit.setDescription("updated description");
    edit.setBranch("release");
    mockMvc
        .perform(
            put("/api/ai/catalog/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(edit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("updated description"))
        .andExpect(jsonPath("$.data.branch").value("release"))
        .andExpect(jsonPath("$.data.currentCommit").value(firstCommit))
        .andExpect(jsonPath("$.data.version").value("1"));

    // branch 前进：检查只更新观察值，current commit 与 manifest 保持原样。
    String secondCommit =
        git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD, "review/SKILL.md", REVIEW_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.checkStatus").value("UPDATE_AVAILABLE"))
        .andExpect(jsonPath("$.data.observedHeadCommit").value(secondCommit))
        .andExpect(jsonPath("$.data.currentCommit").value(firstCommit))
        .andExpect(jsonPath("$.data.skills.length()").value(1))
        .andExpect(jsonPath("$.data.version").value("2"));

    // 发布 card 展示过的 exact commit：commit 与 manifest 在同一次 CAS 中切换。
    mockMvc
        .perform(publishRequest(name, "2", secondCommit))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentCommit").value(secondCommit))
        .andExpect(jsonPath("$.data.observedHeadCommit").value(secondCommit))
        .andExpect(jsonPath("$.data.checkStatus").value("UP_TO_DATE"))
        .andExpect(jsonPath("$.data.skills.length()").value(2))
        .andExpect(jsonPath("$.data.version").value("3"));

    // 陈旧 Card 的删除必须冲突，命中当前 version 才删除。
    mockMvc
        .perform(
            delete("/api/ai/catalog/skill-packages/{name}", name).param("expectedVersion", "2"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            delete("/api/ai/catalog/skill-packages/{name}", name).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/ai/catalog/skill-packages/{name}", name))
        .andExpect(status().isNotFound());
  }

  /** 测试意图：重复 package 名、非法 repository URL 与无法解析的 branch 都被拒绝且不留下半成品行。 */
  @Test
  void rejectsDuplicateAndUnusableCreate() throws Exception {
    GitFixture git = GitFixture.init("create-guard", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("create-guard");

    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    // 同名重复创建是 duplicate，不是覆盖。
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isConflict());
    // 无 scheme 的 URL 与不存在的 branch 都是请求不合法。
    mockMvc
        .perform(createRequest(packageName("bad-url"), "not-a-url"))
        .andExpect(status().isBadRequest());
    SkillPackageCreateDTO badBranch = create(packageName("bad-branch"), git.url());
    badBranch.setBranch("no-such-branch");
    mockMvc
        .perform(
            post("/api/ai/catalog/skill-packages")
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
        .perform(get("/api/ai/catalog/skill-packages/{name}", packageName("missing")))
        .andExpect(status().isNotFound());
    mockMvc.perform(checkRequest(packageName("missing"), "0")).andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/ai/catalog/skill-packages/{name}", packageName("missing"))
                .param("expectedVersion", "0"))
        .andExpect(status().isNotFound());

    SkillPackageEditDTO stale = new SkillPackageEditDTO();
    stale.setExpectedVersion("7");
    stale.setDescription("stale edit");
    stale.setBranch("main");
    mockMvc
        .perform(
            put("/api/ai/catalog/skill-packages/{name}", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isConflict());

    // card 观察到某 commit 之后 branch 又前进：该 version 只认当时展示的 exact commit。
    String observed = git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.observedHeadCommit").value(observed))
        .andExpect(jsonPath("$.data.version").value("1"));
    String advanced =
        git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD, "review/SKILL.md", REVIEW_SKILL_MD));

    // 更旧的 firstCommit 与更新的 advanced 都不是该 version 展示过的 commit。
    mockMvc.perform(publishRequest(name, "1", firstCommit)).andExpect(status().isBadRequest());
    mockMvc.perform(publishRequest(name, "1", advanced)).andExpect(status().isBadRequest());
    // 只有观察值本身可以发布。
    mockMvc
        .perform(publishRequest(name, "1", observed))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentCommit").value(observed))
        .andExpect(jsonPath("$.data.version").value("2"));
  }

  /** 测试意图：仍被 Agent SkillRef 引用的 Package 不能删除，引用保护返回 409 且行保持原状。 */
  @Test
  void rejectsDeleteWhileAgentStillReferencesSkill() throws Exception {
    GitFixture git = GitFixture.init("in-use", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("in-use");
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    referencingAgent(name, "dev");

    mockMvc
        .perform(
            delete("/api/ai/catalog/skill-packages/{name}", name).param("expectedVersion", "0"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get("/api/ai/catalog/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("0"))
        .andExpect(jsonPath("$.data.currentCommit").value(git.firstCommit()));
  }

  /** 测试意图：Skill 在 manifest 中消失但仍被 Agent 引用时，发布也必须拒绝。 */
  @Test
  void rejectsPublishRemovingReferencedSkill() throws Exception {
    GitFixture git = GitFixture.init("publish-in-use", Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String name = packageName("publish-in-use");
    String firstCommit = git.firstCommit();
    mockMvc.perform(createRequest(name, git.url())).andExpect(status().isCreated());
    referencingAgent(name, "dev");

    // 下一个 commit 移除了 dev：仍被引用的 Skill 不得从 Package 中消失。
    String removingCommit = git.commit(Map.of("review/SKILL.md", REVIEW_SKILL_MD));
    mockMvc
        .perform(checkRequest(name, "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.observedHeadCommit").value(removingCommit));
    mockMvc.perform(publishRequest(name, "1", removingCommit)).andExpect(status().isConflict());
    mockMvc
        .perform(get("/api/ai/catalog/skill-packages/{name}", name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentCommit").value(firstCommit))
        .andExpect(jsonPath("$.data.skills[0].name").value("dev"))
        .andExpect(jsonPath("$.data.version").value("1"));
  }

  /** 通过公开 API 建立真实 Skill 引用：Agent config 精确指向某 Package 的 Skill。 */
  private void referencingAgent(String packageName, String skillName) throws Exception {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef(packageName, skillName)));
    config.setSubagents(List.of());
    AgentDefinitionCreateDTO agent = new AgentDefinitionCreateDTO();
    agent.setName("web-agent-" + System.nanoTime());
    agent.setDescription("skill reference guard agent");
    agent.setSystemPrompt("guard");
    agent.setModel("stub/acceptance-stub");
    agent.setConfig(config);
    mockMvc
        .perform(
            post("/api/ai/catalog/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agent)))
        .andExpect(status().isCreated());
  }

  private MockHttpServletRequestBuilder createRequest(String packageName, String repositoryUrl)
      throws Exception {
    return json(post("/api/ai/catalog/skill-packages"), create(packageName, repositoryUrl));
  }

  private MockHttpServletRequestBuilder checkRequest(String packageName, String expectedVersion)
      throws Exception {
    SkillPackageCheckDTO check = new SkillPackageCheckDTO();
    check.setExpectedVersion(expectedVersion);
    return json(post("/api/ai/catalog/skill-packages/{name}/check", packageName), check);
  }

  private MockHttpServletRequestBuilder publishRequest(
      String packageName, String expectedVersion, String targetCommit) throws Exception {
    SkillPackagePublishDTO publish = new SkillPackagePublishDTO();
    publish.setExpectedVersion(expectedVersion);
    publish.setTargetCommit(targetCommit);
    return json(post("/api/ai/catalog/skill-packages/{name}/update", packageName), publish);
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
                .perform(get("/api/ai/catalog/skill-packages"))
                .andExpect(status().isOk())
                .andReturn());
    List<String> names = new ArrayList<>();
    list.forEach(node -> names.add(node.path("packageName").asText()));
    return names;
  }

  private JsonNode data(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(body).path("data");
  }

  private static Path createTestRoot() {
    try {
      return Files.createTempDirectory("kk-studio-skill-web");
    } catch (IOException error) {
      throw new IllegalStateException("failed to create skill test root", error);
    }
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

    /** 每次提交都声明完整 tree：先清空工作区（{@code .git} 除外）再写入给定文件集。 */
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
