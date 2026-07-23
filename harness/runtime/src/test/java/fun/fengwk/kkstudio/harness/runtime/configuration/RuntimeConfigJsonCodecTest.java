package fun.fengwk.kkstudio.harness.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.session.EntryType;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link RuntimeConfigJsonCodec} 的契约与 round-trip 测试，覆盖完整 fixture、canonical 排序、strict 拒绝路径、Kernel
 * {@link EntryType#RUNTIME_CONFIG} 绑定，以及关键负向边界（重复 provider tool name、重复 skill name、重复
 * allowedSubagents、variant 不属于 descriptor、optional null 等）。
 *
 * <p>不变量：codec 必须复用 {@link ToolDescriptorJsonCodec} 与 model codec 的 node API，不复制字段 实现；fixture 在
 * {@code src/test/resources/.../runtime-config.json}。
 */
class RuntimeConfigJsonCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CANONICAL_RESOURCE =
      "/fun/fengwk/kkstudio/harness/runtime/configuration/runtime-config.json";

  private final RuntimeConfigJsonCodec codec = new RuntimeConfigJsonCodec();

  /** 完整 canonical fixture round-trip 必须 bit-identical（按 Provider fixture 风格允许换行差异）。 */
  @Test
  void roundTripsCanonicalFixtureAndMatchesResource() throws Exception {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    String canonicalJson = canonicalJson();
    String expectedJson = canonicalJson.replaceAll("\\s+", "");

    String encoded = codec.encode(snapshot);
    assertEquals(expectedJson, encoded.replaceAll("\\s+", ""));

    RuntimeConfigSnapshot decoded = codec.decode(encoded);
    assertEquals(snapshot, decoded);
    assertEquals(EntryType.RUNTIME_CONFIG, decoded.type());

    // fixture 资源文本与 encode 结果一致（去空白）。
    assertEquals(expectedJson, canonicalJson.replaceAll("\\s+", ""));
  }

  /** Kernel EntryPayload 契约：type() == RUNTIME_CONFIG。 */
  @Test
  void kernelEntryPayloadTypeIsRuntimeConfig() {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    assertSame(EntryType.RUNTIME_CONFIG, snapshot.type());
  }

  /** 同一 snapshot 多次 encode bit-identical。 */
  @Test
  void encodingIsDeterministic() {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    String first = codec.encode(snapshot);
    String second = codec.encode(snapshot);
    assertEquals(first, second);
  }

  /** tools / skills / allowedSubagents 经过 canonical 排序后输入顺序无关。 */
  @Test
  void collectionOrderingIsCanonical() {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    // skills 按 name 字典序。
    List<String> skillNames = new ArrayList<>();
    for (SkillSnapshot skill : snapshot.skills()) {
      skillNames.add(skill.name());
    }
    assertEquals(List.of("code-review", "research"), skillNames);

    // tools 按 (name, version, environmentName) canonical。
    List<String> toolKeys = new ArrayList<>();
    for (ToolBinding binding : snapshot.tools()) {
      toolKeys.add(
          binding.descriptor().name()
              + "@"
              + binding.descriptor().version()
              + "@"
              + (binding.environmentName() == null ? "" : binding.environmentName()));
    }
    assertEquals(List.of("search@v1@", "shell@v1@sandbox"), toolKeys);

    // allowedSubagents 字典序。
    List<String> allowed = snapshot.policy().allowedSubagents();
    assertEquals(List.of("researcher", "writer"), allowed);
  }

  /** 输入逆序的工具列表与正序输出等价。 */
  @Test
  void toolsAreSortedRegardlessOfInputOrder() {
    ToolBinding platformSearch =
        ToolBinding.of(platformDescriptor("search", "v1", "search helper"));
    ToolBinding envShell =
        ToolBinding.of(environmentDescriptor("shell", "v1", "shell", "sandbox"), "sandbox");

    RuntimeConfigSnapshot reversed =
        new RuntimeConfigSnapshot(
            canonicalAgent(),
            canonicalModel(),
            List.of(envShell, platformSearch),
            canonicalSkills(),
            canonicalPolicy(),
            canonicalEnvironment());
    RuntimeConfigSnapshot ordered =
        new RuntimeConfigSnapshot(
            canonicalAgent(),
            canonicalModel(),
            List.of(platformSearch, envShell),
            canonicalSkills(),
            canonicalPolicy(),
            canonicalEnvironment());

    assertEquals(ordered.tools(), reversed.tools());
    assertEquals(codec.encode(ordered), codec.encode(reversed));
  }

  /** 输入逆序的 skill 列表与正序输出等价。 */
  @Test
  void skillsAreSortedRegardlessOfInputOrder() {
    SkillSnapshot codeReview = new SkillSnapshot("code-review", "code review skill", "sandbox");
    SkillSnapshot research = new SkillSnapshot("research", "research skill", "sandbox");

    RuntimeConfigSnapshot reversed =
        new RuntimeConfigSnapshot(
            canonicalAgent(),
            canonicalModel(),
            canonicalTools(),
            List.of(research, codeReview),
            canonicalPolicy(),
            canonicalEnvironment());

    assertEquals(
        List.of("code-review", "research"),
        reversed.skills().stream().map(SkillSnapshot::name).toList());
  }

  /** 输入逆序的 allowedSubagents 与正序输出等价。 */
  @Test
  void allowedSubagentsAreSortedRegardlessOfInputOrder() {
    ExecutionPolicySnapshot reversed =
        new ExecutionPolicySnapshot(16, 8, 4, null, List.of("writer", "researcher"), false);
    assertEquals(List.of("researcher", "writer"), reversed.allowedSubagents());
  }

  // ---------- Strict rejection: top-level JSON ----------

  /** 未知顶层字段必须拒绝。 */
  @Test
  void rejectsUnknownTopLevelField() {
    ObjectNode root = canonicalNode();
    root.put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 agent 必须拒绝。 */
  @Test
  void rejectsMissingAgent() {
    ObjectNode root = canonicalNode();
    root.remove("agent");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 model 必须拒绝。 */
  @Test
  void rejectsMissingModel() {
    ObjectNode root = canonicalNode();
    root.remove("model");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 policy 必须拒绝。 */
  @Test
  void rejectsMissingPolicy() {
    ObjectNode root = canonicalNode();
    root.remove("policy");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 environment 必须拒绝。 */
  @Test
  void rejectsMissingEnvironment() {
    ObjectNode root = canonicalNode();
    root.remove("environment");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 tools 必须拒绝。 */
  @Test
  void rejectsMissingTools() {
    ObjectNode root = canonicalNode();
    root.remove("tools");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 缺 skills 必须拒绝。 */
  @Test
  void rejectsMissingSkills() {
    ObjectNode root = canonicalNode();
    root.remove("skills");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** trailing tokens 拒绝。 */
  @Test
  void rejectsTrailingTokens() {
    String json = codec.encode(canonicalSnapshot()) + " {}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  /** duplicate top-level 字段拒绝。 */
  @Test
  void rejectsDuplicateTopLevelField() {
    String json =
        "{\"agent\":{},\"agent\":{},"
            + "\"model\":{\"descriptor\":{},\"variant\":{}},"
            + "\"tools\":[],\"skills\":[],"
            + "\"policy\":{\"maxTurns\":1,\"maxDepth\":1,\"maxDirectSubagents\":1,"
            + "\"maxTotalSubagents\":null,\"allowedSubagents\":[],\"yoloEnabled\":false},"
            + "\"environment\":{\"environmentName\":null,\"workspaceReference\":null}}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  /** 非 object 顶层节点拒绝。 */
  @Test
  void rejectsNonObjectRoot() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("\"x\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("42"));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
  }

  /** 顶层乱序字段仍可解码（验证顺序校验严格，但顺序不敏感）。 */
  @Test
  void acceptsReorderedTopLevelFields() {
    ObjectNode root = canonicalNode();
    ObjectNode reordered = NODES.objectNode();
    reordered.set("environment", root.get("environment"));
    reordered.set("policy", root.get("policy"));
    reordered.set("skills", root.get("skills"));
    reordered.set("tools", root.get("tools"));
    reordered.set("model", root.get("model"));
    reordered.set("agent", root.get("agent"));
    assertEquals(canonicalSnapshot(), codec.decodeNode(reordered));
  }

  /** 跨字段：JSON 解码时 ENVIRONMENT tool environmentName 不匹配 environment.environmentName 必须拒绝。 */
  @Test
  void decodeRejectsEnvironmentToolBindingMismatch() {
    ObjectNode root = canonicalNode();
    ObjectNode envTool = (ObjectNode) ((ArrayNode) root.get("tools")).get(1);
    envTool.put("environmentName", "other-env");
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("binding environmentName"));
  }

  /** 跨字段：JSON 解码时 skill.sourceEnvironment 不匹配 environment.environmentName 必须拒绝。 */
  @Test
  void decodeRejectsSkillSourceEnvironmentMismatch() {
    ObjectNode root = canonicalNode();
    ObjectNode skill = (ObjectNode) ((ArrayNode) root.get("skills")).get(0);
    skill.put("sourceEnvironment", "other-env");
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("sourceEnvironment"));
  }

  /** 跨字段：JSON 解码时 model.descriptor.tools=false 但 tools 非空必须拒绝。 */
  @Test
  void decodeRejectsToolsWhenDescriptorDisablesTools() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("model").get("descriptor")).put("tools", false);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("model.descriptor().tools()"));
  }

  // ---------- Strict rejection: tools ----------

  /** tools 数组存在重复 provider tool name 必须拒绝。 */
  @Test
  void rejectsDuplicateProviderToolName() {
    ObjectNode root = canonicalNode();
    ArrayNode tools = (ArrayNode) root.get("tools");
    ObjectNode duplicate = ((ObjectNode) tools.get(0)).deepCopy();
    tools.add(duplicate);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("duplicate provider tool name"));
  }

  /** tools[] 缺 descriptor 必须拒绝。 */
  @Test
  void rejectsToolMissingDescriptor() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("tools").get(0)).remove("descriptor");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** tools[] 未知字段必须拒绝。 */
  @Test
  void rejectsToolUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("tools").get(0)).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** tools[].environmentName 空白字符串必须拒绝。 */
  @Test
  void rejectsBlankToolEnvironmentName() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("tools").get(1)).put("environmentName", " ");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  // ---------- Strict rejection: skills ----------

  /** skills 含重复 name 必须拒绝。 */
  @Test
  void rejectsDuplicateSkillName() {
    ObjectNode root = canonicalNode();
    ArrayNode skills = (ArrayNode) root.get("skills");
    ObjectNode dup = ((ObjectNode) skills.get(0)).deepCopy();
    skills.add(dup);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("duplicate name"));
  }

  /** skills 缺字段必须拒绝。 */
  @Test
  void rejectsSkillMissingField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("skills").get(0)).remove("description");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** skills 未知字段必须拒绝。 */
  @Test
  void rejectsSkillUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("skills").get(0)).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** skills 空白字段必须拒绝。 */
  @Test
  void rejectsBlankSkillField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("skills").get(0)).put("name", " ");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  // ---------- Strict rejection: policy ----------

  /** policy 缺字段必须拒绝。 */
  @Test
  void rejectsPolicyMissingField() {
    ObjectNode root = canonicalNode();
    root.get("policy");
    ObjectNode policy = (ObjectNode) root.get("policy");
    policy.remove("yoloEnabled");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy 未知字段必须拒绝。 */
  @Test
  void rejectsPolicyUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).put("extra", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy maxTurns <= 0 必须拒绝。 */
  @Test
  void rejectsNonPositiveMaxTurns() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).put("maxTurns", 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy maxTotalSubagents 显式 0 必须拒绝。 */
  @Test
  void rejectsZeroMaxTotalSubagents() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).put("maxTotalSubagents", 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy maxTotalSubagents 显式 null 合法（decoder 接受 null）。 */
  @Test
  void acceptsNullMaxTotalSubagents() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).putNull("maxTotalSubagents");
    RuntimeConfigSnapshot decoded = codec.decodeNode(root);
    assertNull(decoded.policy().maxTotalSubagents());
  }

  /** policy.allowedSubagents 含空白字符串必须拒绝。 */
  @Test
  void rejectsBlankAllowedSubagent() {
    ObjectNode root = canonicalNode();
    ArrayNode allowed = (ArrayNode) root.get("policy").get("allowedSubagents");
    allowed.set(0, NODES.textNode(" "));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy.allowedSubagents 含重复 entry 必须拒绝。 */
  @Test
  void rejectsDuplicateAllowedSubagent() {
    ObjectNode root = canonicalNode();
    ArrayNode allowed = (ArrayNode) root.get("policy").get("allowedSubagents");
    allowed.add(NODES.textNode("researcher"));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  /** policy.allowedSubagents 非 array 必须拒绝。 */
  @Test
  void rejectsNonArrayAllowedSubagents() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).put("allowedSubagents", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** policy.yoloEnabled 非 boolean 必须拒绝。 */
  @Test
  void rejectsNonBooleanYoloEnabled() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("policy")).put("yoloEnabled", "yes");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  // ---------- Strict rejection: environment ----------

  /** environment.environmentName 空白必须拒绝。 */
  @Test
  void rejectsBlankEnvironmentName() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("environment")).put("environmentName", "");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** environment.environmentName 首尾空白必须拒绝。 */
  @Test
  void rejectsSurroundingWhitespaceEnvironmentName() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("environment")).put("environmentName", " default");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** environment.workspaceReference null 合法。 */
  @Test
  void acceptsNullWorkspaceReference() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("environment")).putNull("workspaceReference");
    RuntimeConfigSnapshot decoded = codec.decodeNode(root);
    assertNull(decoded.environment().workspaceReference());
  }

  /** environment 未知字段必须拒绝。 */
  @Test
  void rejectsEnvironmentUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("environment")).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  // ---------- Strict rejection: agent ----------

  /** agent.systemPrompt null 合法，规范化成 ""。 */
  @Test
  void acceptsNullAgentSystemPrompt() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).putNull("systemPrompt");
    RuntimeConfigSnapshot decoded = codec.decodeNode(root);
    assertEquals("", decoded.agent().systemPrompt());
  }

  /** agent.systemPrompt 显式空字符串也合法（与 null 等价）。 */
  @Test
  void acceptsEmptyAgentSystemPrompt() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).put("systemPrompt", "");
    RuntimeConfigSnapshot decoded = codec.decodeNode(root);
    assertEquals("", decoded.agent().systemPrompt());
  }

  /** agent.definitionId 非正整数必须拒绝。 */
  @Test
  void rejectsNonPositiveAgentDefinitionId() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).put("definitionId", 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** agent.definitionId 非整数必须拒绝。 */
  @Test
  void rejectsNonIntegerAgentDefinitionId() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).put("definitionId", "1");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** agent.name 空白必须拒绝。 */
  @Test
  void rejectsBlankAgentName() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).put("name", " ");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** agent 未知字段必须拒绝。 */
  @Test
  void rejectsAgentUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("agent")).put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  // ---------- Strict rejection: model ----------

  /** model.variant 必须属于 model.descriptor.variants；外部替换 variant 必须拒绝。 */
  @Test
  void rejectsVariantNotInDescriptor() {
    ModelVariant foreignVariant =
        new ModelVariant("alien", null, 0.7, 0.9, null, null, null, List.of(), null);
    ObjectNode root = canonicalNode();
    root.get("model").get("variant");
    ObjectNode variant = (ObjectNode) root.get("model").get("variant");
    variant.put("id", "alien");
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
    assertTrue(error.getMessage().contains("variant"));
    assertNotNull(foreignVariant); // 仅用于 IDE：保留构造
  }

  /** model 未知字段必须拒绝。 */
  @Test
  void rejectsModelUnknownField() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("model")).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** model.descriptor 内部非法必须拒绝（直接复用 model codec 的严格性）。 */
  @Test
  void rejectsMalformedDescriptor() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("model").get("descriptor")).put("providerType", "LEGACY");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** 顶层 model 显式 JSON null 必须拒绝（视为缺失），不让其渗入共享 codec 触发 NPE。 */
  @Test
  void rejectsExplicitNullTopLevelModel() {
    ObjectNode root = canonicalNode();
    root.putNull("model");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** model.descriptor 显式 JSON null 必须拒绝（视为缺失）。 */
  @Test
  void rejectsExplicitNullModelDescriptor() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("model")).putNull("descriptor");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** tools[].descriptor 显式 JSON null 必须拒绝。 */
  @Test
  void rejectsExplicitNullToolDescriptor() {
    ObjectNode root = canonicalNode();
    ((ObjectNode) root.get("tools").get(0)).putNull("descriptor");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  /** agent / policy / environment 顶层显式 JSON null 必须拒绝。 */
  @Test
  void rejectsExplicitNullOtherTopLevelObjects() {
    for (String field : new String[] {"agent", "policy", "environment"}) {
      ObjectNode root = canonicalNode();
      root.putNull(field);
      assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root), field);
    }
  }

  // ---------- Secret hygiene ----------

  /** snapshot 不持有 secret value：wire 中无 apiKey / token / password / secret 字段。 */
  @Test
  void canonicalWireContainsNoSecretField() throws Exception {
    String json = codec.encode(canonicalSnapshot()).toLowerCase();
    for (String forbidden : List.of("apikey", "token", "password", "secret", "credential_value")) {
      assertFalse(
          json.contains("\"" + forbidden + "\""), "wire must not carry field: " + forbidden);
    }
  }

  /** credential reference 通过 providerResourceId 表达，名称稳定且不是 secret value。 */
  @Test
  void credentialReferenceIsStableProviderResourceId() throws Exception {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    String json = codec.encode(snapshot);
    ObjectNode root = (ObjectNode) MAPPER.readTree(json);
    assertEquals(9001L, root.path("model").path("descriptor").path("providerResourceId").asLong());
    // 不出现任何 secret-like 字段。
    assertNotEquals(-1, json.indexOf("\"providerResourceId\""));
  }

  // ---------- Snapshot constructor invariants ----------

  /** AgentSnapshot.systemPrompt null 规范化成 ""。 */
  @Test
  void agentSnapshotNormalizesNullSystemPromptToEmpty() {
    AgentSnapshot agent = new AgentSnapshot(1L, "x", null);
    assertEquals("", agent.systemPrompt());
  }

  /** AgentSnapshot.definitionId <= 0 必须拒绝。 */
  @Test
  void agentSnapshotRejectsNonPositiveDefinitionId() {
    assertThrows(IllegalArgumentException.class, () -> new AgentSnapshot(0, "x", ""));
    assertThrows(IllegalArgumentException.class, () -> new AgentSnapshot(-1, "x", ""));
  }

  /** AgentSnapshot.name 空白必须拒绝。 */
  @Test
  void agentSnapshotRejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () -> new AgentSnapshot(1L, "", ""));
    assertThrows(IllegalArgumentException.class, () -> new AgentSnapshot(1L, " ", ""));
  }

  /** ModelSnapshot.variant 必须属于 descriptor.variants。 */
  @Test
  void modelSnapshotRejectsForeignVariant() {
    ModelDescriptor descriptor = canonicalDescriptor();
    ModelVariant foreign =
        new ModelVariant("alien", null, 0.1, 0.9, null, null, null, List.of(), null);
    assertThrows(IllegalArgumentException.class, () -> new ModelSnapshot(descriptor, foreign));
  }

  /** ModelSnapshot 非空校验。 */
  @Test
  void modelSnapshotRejectsNullFields() {
    ModelDescriptor descriptor = canonicalDescriptor();
    ModelVariant variant = canonicalDescriptor().variants().get(0);
    assertThrows(NullPointerException.class, () -> new ModelSnapshot(null, variant));
    assertThrows(NullPointerException.class, () -> new ModelSnapshot(descriptor, null));
  }

  /** SkillSnapshot 全部非空白。 */
  @Test
  void skillSnapshotRejectsBlankFields() {
    assertThrows(IllegalArgumentException.class, () -> new SkillSnapshot("", "d", "e"));
    assertThrows(IllegalArgumentException.class, () -> new SkillSnapshot("n", " ", "e"));
    assertThrows(IllegalArgumentException.class, () -> new SkillSnapshot("n", "d", " "));
  }

  /** ExecutionPolicySnapshot 正数校验。 */
  @Test
  void executionPolicyRejectsNonPositiveFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(0, 1, 1, null, List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(1, 0, 1, null, List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(1, 1, 0, null, List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(1, 1, 1, 0, List.of(), false));
  }

  /** ExecutionPolicySnapshot.allowedSubagents 非空 / 唯一 / canonical 字典序。 */
  @Test
  void executionPolicyCanonicalizesAllowedSubagents() {
    ExecutionPolicySnapshot policy =
        new ExecutionPolicySnapshot(1, 1, 1, null, List.of("writer", "researcher"), false);
    assertEquals(List.of("researcher", "writer"), policy.allowedSubagents());
  }

  @Test
  void executionPolicyRejectsDuplicateAllowedSubagent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(1, 1, 1, null, List.of("x", "x"), false));
  }

  @Test
  void executionPolicyRejectsBlankAllowedSubagent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionPolicySnapshot(1, 1, 1, null, List.of("x", " "), false));
  }

  /** EnvironmentSnapshot 接受 null，present 时非空白且无首尾空白。 */
  @Test
  void environmentSnapshotAcceptsBothNull() {
    EnvironmentSnapshot env = new EnvironmentSnapshot(null, null);
    assertNull(env.environmentName());
    assertNull(env.workspaceReference());
  }

  @Test
  void environmentSnapshotRejectsBlankOrSurroundingWhitespace() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot("", null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(" ", null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(" default", null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot("default ", null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(null, " ref "));
  }

  /** RuntimeConfigSnapshot.tools 中含重复 provider tool name 必须拒绝。 */
  @Test
  void runtimeConfigRejectsDuplicateToolName() {
    ToolBinding platformSearch =
        ToolBinding.of(platformDescriptor("search", "v1", "search helper"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                canonicalAgent(),
                canonicalModel(),
                List.of(platformSearch, platformSearch),
                canonicalSkills(),
                canonicalPolicy(),
                canonicalEnvironment()));
  }

  /** RuntimeConfigSnapshot.skills 中含重复 name 必须拒绝。 */
  @Test
  void runtimeConfigRejectsDuplicateSkillName() {
    SkillSnapshot skill = new SkillSnapshot("dup", "d", "sandbox");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                canonicalAgent(),
                canonicalModel(),
                canonicalTools(),
                List.of(skill, skill),
                canonicalPolicy(),
                canonicalEnvironment()));
  }

  /** 跨字段：tools 非空时 model.descriptor().tools() 必须为 true。 */
  @Test
  void runtimeConfigRejectsNonEmptyToolsWhenDescriptorDisablesTools() {
    ModelDescriptor descriptorNoTools = noToolsDescriptor();
    ModelSnapshot modelNoTools =
        new ModelSnapshot(descriptorNoTools, descriptorNoTools.variants().get(0));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuntimeConfigSnapshot(
                    canonicalAgent(),
                    modelNoTools,
                    canonicalTools(),
                    canonicalSkills(),
                    canonicalPolicy(),
                    canonicalEnvironment()));
    assertTrue(error.getMessage().contains("model.descriptor().tools()"));
  }

  /** 跨字段：ENVIRONMENT tool 的 environmentName 必须等于 environment.environmentName。 */
  @Test
  void runtimeConfigRejectsEnvironmentToolBindingMismatch() {
    ToolBinding wrongBinding =
        ToolBinding.of(environmentDescriptor("shell", "v1", "shell", "sandbox"), "other-env");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuntimeConfigSnapshot(
                    canonicalAgent(),
                    canonicalModel(),
                    List.of(wrongBinding),
                    List.of(),
                    canonicalPolicy(),
                    canonicalEnvironment()));
    assertTrue(error.getMessage().contains("binding environmentName"));
  }

  /** 跨字段：ENVIRONMENT tool 缺 environmentName 被 {@link ToolBinding} 自身校验拒绝——本快照不可越层构造。 */
  @Test
  void environmentToolWithoutEnvironmentNameRejectedByToolBinding() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ToolBinding.of(
                    new ToolDescriptor(
                        "shell",
                        "v1",
                        "shell",
                        "shell",
                        new ToolParamsSchema(null, Map.of(), Set.of(), true),
                        ToolExecutionLocation.ENVIRONMENT,
                        ToolSideEffect.NON_IDEMPOTENT,
                        Duration.ofMillis(500))));
    assertTrue(error.getMessage().contains("ENVIRONMENT tools require environmentName"));
  }

  /** 跨字段：skills 非空时 environment.environmentName 必须存在。 */
  @Test
  void runtimeConfigRejectsSkillsWithoutEnvironmentName() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuntimeConfigSnapshot(
                    canonicalAgent(),
                    canonicalModel(),
                    List.of(),
                    List.of(new SkillSnapshot("a", "b", "sandbox")),
                    canonicalPolicy(),
                    new EnvironmentSnapshot(null, null)));
    assertTrue(error.getMessage().contains("skills is non-empty"));
  }

  /** 跨字段：skill.sourceEnvironment 必须等于 environment.environmentName。 */
  @Test
  void runtimeConfigRejectsSkillSourceEnvironmentMismatch() {
    SkillSnapshot foreignSkill = new SkillSnapshot("a", "b", "other-env");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuntimeConfigSnapshot(
                    canonicalAgent(),
                    canonicalModel(),
                    List.of(),
                    List.of(foreignSkill),
                    canonicalPolicy(),
                    canonicalEnvironment()));
    assertTrue(error.getMessage().contains("sourceEnvironment"));
  }

  /** 跨字段 PLATFORM 工具不要求 environment。 */
  @Test
  void runtimeConfigAcceptsPlatformToolWithoutEnvironmentName() {
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(
            canonicalAgent(),
            canonicalModel(),
            List.of(ToolBinding.of(platformDescriptor("search", "v1", "search helper"))),
            List.of(),
            canonicalPolicy(),
            canonicalEnvironment());
    assertEquals(1, snapshot.tools().size());
    assertEquals(ToolExecutionLocation.PLATFORM, snapshot.tools().get(0).location());
  }

  /** 跨字段空 tools + 空 skills 仍然合法（environment.environmentName 可空）。 */
  @Test
  void runtimeConfigAcceptsEmptyToolsAndSkills() {
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(
            canonicalAgent(),
            canonicalModel(),
            List.of(),
            List.of(),
            canonicalPolicy(),
            new EnvironmentSnapshot(null, null));
    assertEquals(0, snapshot.tools().size());
    assertEquals(0, snapshot.skills().size());
  }

  private static ModelDescriptor noToolsDescriptor() {
    return new ModelDescriptor(
        9001L,
        8001L,
        ProviderType.OPENAI,
        "gpt-5-mini",
        "GPT-5 Mini",
        200_000L,
        16_384L,
        EnumSet.of(ModelInputModality.TEXT),
        false,
        true,
        List.of(
            new ModelVariant("balanced", null, 0.2, 0.9, null, null, null, List.of("STOP"), null)),
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            new BigDecimal("1.5"),
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        PromptCachePolicy.disabled());
  }

  // ---------- Cross-codec: node API 一致性 ----------

  /** String API 与 node API 在 encode 端等价。 */
  @Test
  void nodeApiEncodeIsConsistentWithStringApi() throws Exception {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    String encodedString = codec.encode(snapshot);
    ObjectNode encodedNode = codec.encodeNode(snapshot);
    assertEquals(encodedString, MAPPER.writeValueAsString(encodedNode));
  }

  /** String API 与 node API 在 decode 端等价。 */
  @Test
  void nodeApiDecodeIsConsistentWithStringApi() throws Exception {
    RuntimeConfigSnapshot snapshot = canonicalSnapshot();
    String encoded = codec.encode(snapshot);
    ObjectNode tree = (ObjectNode) MAPPER.readTree(encoded);
    assertEquals(codec.decode(encoded), codec.decodeNode(tree));
  }

  // ---------- Fixture / helpers ----------

  private static RuntimeConfigSnapshot canonicalSnapshot() {
    return new RuntimeConfigSnapshot(
        canonicalAgent(),
        canonicalModel(),
        canonicalTools(),
        canonicalSkills(),
        canonicalPolicy(),
        canonicalEnvironment());
  }

  private static AgentSnapshot canonicalAgent() {
    return new AgentSnapshot(1001L, "primary-agent", "You are a careful assistant.");
  }

  private static ModelSnapshot canonicalModel() {
    ModelDescriptor descriptor = canonicalDescriptor();
    return new ModelSnapshot(descriptor, descriptor.variants().get(0));
  }

  private static ModelDescriptor canonicalDescriptor() {
    return new ModelDescriptor(
        9001L,
        8001L,
        ProviderType.OPENAI,
        "gpt-5-mini",
        "GPT-5 Mini",
        200_000L,
        16_384L,
        EnumSet.of(
            ModelInputModality.TEXT,
            ModelInputModality.IMAGE,
            ModelInputModality.AUDIO,
            ModelInputModality.VIDEO,
            ModelInputModality.DOCUMENT),
        true,
        true,
        List.of(
            new ModelVariant("balanced", null, 0.2, 0.9, null, null, null, List.of("STOP"), null)),
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            new BigDecimal("1.5"),
            "v1",
            new BigDecimal("3.000000000000"),
            new BigDecimal("6.000000000000"),
            new BigDecimal("0.300000000000"),
            new BigDecimal("3.750000000000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        PromptCachePolicy.breakpointsShort(canonicalCapability()));
  }

  private static PromptCacheCapability canonicalCapability() {
    return PromptCacheCapability.breakpoints(
        EnumSet.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM));
  }

  private static List<ToolBinding> canonicalTools() {
    return List.of(
        new ToolBinding(platformDescriptor("search", "v1", "search helper"), null),
        new ToolBinding(
            environmentDescriptor("shell", "v1", "remote shell", "sandbox"), "sandbox"));
  }

  private static List<SkillSnapshot> canonicalSkills() {
    return List.of(
        new SkillSnapshot("research", "research skill", "sandbox"),
        new SkillSnapshot("code-review", "code review skill", "sandbox"));
  }

  private static ExecutionPolicySnapshot canonicalPolicy() {
    return new ExecutionPolicySnapshot(16, 8, 4, null, List.of("researcher", "writer"), false);
  }

  private static EnvironmentSnapshot canonicalEnvironment() {
    return new EnvironmentSnapshot("sandbox", null);
  }

  private static ToolDescriptor platformDescriptor(
      String name, String version, String description) {
    return new ToolDescriptor(
        name,
        version,
        description,
        name,
        new ToolParamsSchema(
            "params",
            Map.of("query", new ToolStringSchema("search query")),
            Set.of("query"),
            false),
        ToolExecutionLocation.PLATFORM,
        ToolSideEffect.READ_ONLY,
        Duration.ofMillis(1000));
  }

  private static ToolDescriptor environmentDescriptor(
      String name, String version, String description, String env) {
    return new ToolDescriptor(
        name,
        version,
        description,
        name,
        new ToolParamsSchema(null, Map.of("cmd", new ToolStringSchema("command")), Set.of(), true),
        ToolExecutionLocation.ENVIRONMENT,
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ofMillis(500));
  }

  private static ObjectNode canonicalNode() {
    try {
      return (ObjectNode) MAPPER.readTree(canonicalJson());
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String canonicalJson() {
    try (InputStream input =
        Objects.requireNonNull(
            RuntimeConfigJsonCodecTest.class.getResourceAsStream(CANONICAL_RESOURCE),
            CANONICAL_RESOURCE)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
