package fun.fengwk.kkstudio.web.configsync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 配置同步 Web 集成测试的共享基座。
 *
 * <p>复用 {@link WebPostgresTestSupport} 的进程级 disposable PostgreSQL 与当前 Flyway schema；把 Skill 的 Git
 * bare cache 重定向到进程临时目录，避免写入模块工作树。提供 MockMvc 便捷读写、YAML 结构解析与真实 JGit 临时仓库构造器。
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ConfigSyncTestSupport extends WebPostgresTestSupport {

  /** Git 仓库与 Platform bare cache 都落在进程临时目录。 */
  private static final Path TEST_ROOT = createTestRoot();

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;

  /** 插件凭据不参与配置同步；停用无关后台刷新，避免重建测试 Schema 时与后台查询竞争。 */
  @MockitoBean private PluginCredentialRefreshDispatcher pluginCredentialRefreshDispatcher;

  @DynamicPropertySource
  static void overrideSkillCacheRoot(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.catalog.skill.cache-root", () -> TEST_ROOT.resolve("cache").toString());
  }

  /** 发起请求并断言指定 HTTP 状态，返回原始结果。 */
  protected MvcResult perform(MockHttpServletRequestBuilder builder, int expectedStatus)
      throws Exception {
    return mockMvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
  }

  /** JSON body 请求；断言 200/201 后返回 {@code data} 节点。 */
  protected JsonNode postData(String path, Object body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
    return data(result);
  }

  /** PUT JSON body 请求；断言 2xx 后返回 {@code data} 节点。 */
  protected JsonNode putData(String path, Object body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                put(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
    return data(result);
  }

  /** GET 请求；断言 2xx 后返回 {@code data} 节点。 */
  protected JsonNode getData(String path) throws Exception {
    MvcResult result = mockMvc.perform(get(path)).andExpect(status().is2xxSuccessful()).andReturn();
    return data(result);
  }

  protected JsonNode data(MvcResult result) throws Exception {
    return objectMapper
        .reader()
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .readTree(result.getResponse().getContentAsString())
        .path("data");
  }

  protected JsonNode envelope(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  protected String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  protected static List<String> listText(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.asText()));
    return values;
  }

  /** 解析导出 YAML 为纯结构 Map，用于断言顶层集合与字段形状。 */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> parseYaml(String yaml) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(yaml);
  }

  /** 导出当前完整 settings YAML，供必须完整七节的导入用例构造文件。 */
  protected String exportedSettingsYaml() throws Exception {
    return postData(
            "/api/settings/sync/export",
            Map.of("items", List.of(Map.of("kind", "settings", "name", "settings"))))
        .path("yaml")
        .asText();
  }

  /** 解析 YAML 后按需改写再回写为纯结构文本；避免手写完整七节。 */
  protected static String mutateYaml(String yaml, Consumer<Map<String, Object>> mutator) {
    Map<String, Object> document = parseYaml(yaml);
    mutator.accept(document);
    return new Yaml().dump(document);
  }

  /** 一个合法、带可辨识精度与 protocolOptionsJson 的 Model config。 */
  protected static AgentModelConfigDTO modelConfig(String protocolOptionsJson, String inputPrice) {
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(4096);
    limit.setOutput(1024);

    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(AgentModelInputModality.TEXT));

    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency("USD");
    pricing.setPricingTier("default");
    pricing.setServiceTier("default");
    pricing.setServiceTierMultiplier(new BigDecimal("1"));
    pricing.setVersion("v1");
    pricing.setInputPerMillionTokens(new BigDecimal(inputPrice));
    pricing.setOutputPerMillionTokens(new BigDecimal("2.5"));
    pricing.setCacheReadPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWritePerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWriteLongPerMillionTokens(BigDecimal.ZERO);
    pricing.setReasoningPerMillionTokens(BigDecimal.ZERO);

    AgentModelVariantDTO variant = new AgentModelVariantDTO();
    variant.setId("default");
    variant.setProtocolOptionsJson(protocolOptionsJson);

    AgentModelConfigDTO config = new AgentModelConfigDTO();
    config.setLimit(limit);
    config.setAbilities(abilities);
    config.setPricing(pricing);
    config.setDefaultVariant("default");
    config.setVariants(List.of(variant));
    return config;
  }

  protected static AgentDefinitionConfigDTO agentConfig(
      List<String> tools, List<SkillRefDTO> skills, List<String> subagents) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(tools);
    config.setSkills(skills);
    config.setSubagents(subagents);
    return config;
  }

  protected static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private static Path createTestRoot() {
    try {
      return Files.createTempDirectory("kk-studio-configsync-web");
    } catch (IOException error) {
      throw new IllegalStateException("failed to create config sync test root", error);
    }
  }

  /**
   * 临时 JGit 仓库：每次提交完整声明 tree，并让 {@code main} 指向新 commit。
   *
   * <p>测试用它制造「HEAD 已前进」的场景，验证导入按 YAML 中的 exact commit 恢复，而不是回退到最新 HEAD。
   */
  protected static final class GitFixture {

    private final Path directory;
    private final Git git;
    private final String firstCommit;

    private GitFixture(Path directory, Git git, String firstCommit) {
      this.directory = directory;
      this.git = git;
      this.firstCommit = firstCommit;
    }

    static GitFixture init(String prefix, Map<String, String> files) throws Exception {
      Path directory = Files.createTempDirectory("kk-studio-configsync-repo-" + prefix);
      Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call();
      String firstCommit = commit(git, directory, files);
      return new GitFixture(directory, git, firstCommit);
    }

    String firstCommit() {
      return firstCommit;
    }

    /** 写入完整文件集合提交一次，推进 {@code main}，返回 commit id。 */
    String commit(Map<String, String> files) throws Exception {
      return commit(git, directory, files);
    }

    String url() {
      return directory.toUri().toString();
    }

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
        RefUpdate update = repository.updateRef("refs/heads/main");
        update.setNewObjectId(commit.getId());
        update.setForceUpdate(true);
        update.update();
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
  }
}
