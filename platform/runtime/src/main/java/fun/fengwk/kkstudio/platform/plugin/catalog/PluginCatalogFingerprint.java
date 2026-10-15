package fun.fengwk.kkstudio.platform.plugin.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjectorContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomEntryTypeContribution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Plugin catalog 的确定性身份指纹：配置定义指纹与冻结两域目录指纹。
 *
 * <p>指纹只从实际冻结的定义取元数据，绝不序列化 Tool、Adapter、Projector 或 PluginInstance：配置定义指纹覆盖 values schema、唯一默认值、
 * secrets 字段声明与 authSpec；两域目录指纹覆盖 contributor descriptor/requires、ToolContribution
 * 身份/优先级/stateAccesses/definition， custom entry 与 context projector 的 ownership/优先级，以及 Canvas
 * function 的完整 definition（不含远端健康或动态 unavailableReason）。
 *
 * <p>所有元数据先装配成 Jackson 严格树模型，再递归按对象键字典序排序、以数组表达集合边界（长度即计数、结构无歧义），最后对 Jackson 的紧凑 UTF-8 输出取 JDK
 * SHA-256（64 位小写十六进制）。因此输入集合顺序、对象键顺序与 DB jsonb 回读后的键顺序都不改变指纹；数组语义顺序与 {@link java.math.BigDecimal}
 * 数字精度保持不变。
 */
public final class PluginCatalogFingerprint {

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
  private static final AgentToolDefinitionJsonCodec TOOL_DEFINITION_CODEC =
      new AgentToolDefinitionJsonCodec();
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private PluginCatalogFingerprint() {}

  /** 对给定字节取 64 位小写 SHA-256；调用方负责领域分隔。 */
  public static String sha256Hex(byte[] input) {
    Objects.requireNonNull(input, "input");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }

  /** 文本是否为 64 位小写 SHA-256 指纹。 */
  public static boolean isFingerprint(String value) {
    return value != null && FINGERPRINT.matcher(value).matches();
  }

  /** 配置定义指纹：{@link PluginConfigSchema} 的 values schema、默认值、secrets 声明，加 {@link PluginAuthSpec}。 */
  public static String schema(PluginConfigSchema schema, PluginAuthSpec authSpec) {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(authSpec, "authSpec");
    ObjectNode root = NODES.objectNode();
    root.put("kind", "plugin-schema");
    root.set("values", SCHEMA_CODEC.encodeNode(schema.valuesSchema()));
    root.set("defaults", JsonValues.readTree(schema.defaultsJson()));
    ObjectNode secrets = root.putObject("secrets");
    for (Map.Entry<String, String> entry : new TreeMap<>(schema.secrets()).entrySet()) {
      secrets.put(entry.getKey(), entry.getValue());
    }
    root.set("authSpec", authSpecNode(authSpec));
    return digest(root);
  }

  /** 完整冻结两域目录指纹：包含所有内置与插件贡献。 */
  static String catalog(HarnessCatalog harness, CanvasFunctionCatalog canvas) {
    return digest(catalogNode("plugin-catalog", harness, canvas, null, null));
  }

  /** 单插件能力指纹：按 owner map 只筛选该插件实际冻结的两域贡献。 */
  static String capability(
      HarnessCatalog harness,
      CanvasFunctionCatalog canvas,
      Set<ContributorId> ownedContributors,
      Set<String> ownedFunctions) {
    Objects.requireNonNull(ownedContributors, "ownedContributors");
    Objects.requireNonNull(ownedFunctions, "ownedFunctions");
    return digest(
        catalogNode("plugin-capability", harness, canvas, ownedContributors, ownedFunctions));
  }

  private static ObjectNode authSpecNode(PluginAuthSpec authSpec) {
    ObjectNode node = NODES.objectNode();
    switch (authSpec) {
      case PluginAuthSpec.None ignored -> node.put("type", "none");
      case PluginAuthSpec.StaticKey staticKey -> {
        node.put("type", "static-key");
        node.put("secretField", staticKey.secretField());
        ArrayNode contextFields = node.putArray("contextFields");
        for (String field : sortedNames(staticKey.contextFields())) {
          contextFields.add(field);
        }
      }
      case PluginAuthSpec.DeepLink deepLink -> {
        node.put("type", "deep-link");
        ArrayNode regions = node.putArray("regions");
        for (String region : deepLink.regions()) {
          regions.add(region);
        }
      }
    }
    return node;
  }

  private static ObjectNode catalogNode(
      String kind,
      HarnessCatalog harness,
      CanvasFunctionCatalog canvas,
      Set<ContributorId> owners,
      Set<String> ownedFunctions) {
    Objects.requireNonNull(harness, "harness");
    Objects.requireNonNull(canvas, "canvas");
    ObjectNode root = NODES.objectNode();
    root.put("kind", kind);

    ArrayNode contributors = root.putArray("contributors");
    for (ContributorDescriptor descriptor : harness.descriptors()) {
      if (owners != null && !owners.contains(descriptor.id())) {
        continue;
      }
      ObjectNode node = contributors.addObject();
      node.put("id", descriptor.id().value());
      node.put("name", descriptor.name());
      node.put("version", descriptor.version());
      ArrayNode requires = node.putArray("requires");
      for (ContributorId required : descriptor.requires()) {
        requires.add(required.value());
      }
    }

    ArrayNode tools = root.putArray("tools");
    for (ToolContribution tool : harness.tools()) {
      if (owners != null && !owners.contains(tool.id().contributorId())) {
        continue;
      }
      ObjectNode node = tools.addObject();
      node.put("contributorId", tool.id().contributorId().value());
      node.put("localName", tool.id().localName());
      node.put("priority", tool.priority());
      node.set("definition", TOOL_DEFINITION_CODEC.encodeNode(tool.definition()));
      node.put("environmentSupport", tool.requirements().environmentSupport().name());
      node.put(
          "requiredEnvironmentId",
          tool.requirements().requiredEnvironmentId() == null
              ? null
              : tool.requirements().requiredEnvironmentId().toString());
      ArrayNode stateAccesses = node.putArray("stateAccesses");
      for (StateDeclaration access : tool.requirements().stateAccesses()) {
        ObjectNode accessNode = stateAccesses.addObject();
        accessNode.put("customType", access.customType());
        accessNode.put("mode", access.mode().name());
      }
    }

    ArrayNode customEntryTypes = root.putArray("customEntryTypes");
    for (CustomEntryTypeContribution entry : harness.customEntryTypes()) {
      if (owners != null && !owners.contains(entry.id().contributorId())) {
        continue;
      }
      ObjectNode node = customEntryTypes.addObject();
      node.put("contributorId", entry.id().contributorId().value());
      node.put("localName", entry.id().localName());
      node.put("customType", entry.customType());
      node.put("priority", entry.priority());
    }

    ArrayNode contextProjectors = root.putArray("contextProjectors");
    for (ContextProjectorContribution projector : harness.contextProjectors()) {
      if (owners != null && !owners.contains(projector.id().contributorId())) {
        continue;
      }
      ObjectNode node = contextProjectors.addObject();
      node.put("contributorId", projector.id().contributorId().value());
      node.put("localName", projector.id().localName());
      node.put("priority", projector.priority());
    }

    ArrayNode functions = root.putArray("canvasFunctions");
    for (CanvasFunctionCatalog.RegisteredFunction registered : canvas.list()) {
      if (ownedFunctions != null && !ownedFunctions.contains(registered.function().name())) {
        continue;
      }
      ObjectNode node = functions.addObject();
      node.put("name", registered.function().name());
      node.put("description", registered.function().description());
      node.set("argsSchema", JsonValues.readTree(registered.function().argsSchema().write()));
      ArrayNode outputs = node.putArray("outputs");
      for (CanvasFunctionOutputSpec output : registered.function().outputs()) {
        ObjectNode outputNode = outputs.addObject();
        outputNode.put("kind", output.kind().name());
        outputNode.put("name", output.name());
      }
      node.set("referencePolicy", referencePolicyNode(registered.function().referencePolicy()));
    }
    return root;
  }

  private static ObjectNode referencePolicyNode(CanvasFunctionReferencePolicy policy) {
    ObjectNode node = NODES.objectNode();
    node.put("maxReferences", policy.maxReferences());
    List<CanvasResourceKind> allowedKinds = new ArrayList<>(policy.allowedKinds());
    allowedKinds.sort(Comparator.naturalOrder());
    ArrayNode kinds = node.putArray("allowedKinds");
    for (CanvasResourceKind kind : allowedKinds) {
      kinds.add(kind.name());
    }
    ObjectNode maxByKind = node.putObject("maxByKind");
    for (Map.Entry<CanvasResourceKind, Integer> entry :
        new TreeMap<CanvasResourceKind, Integer>(policy.maxByKind()).entrySet()) {
      maxByKind.put(entry.getKey().name(), entry.getValue().intValue());
    }
    return node;
  }

  /** 递归按对象键字典序排序；数组顺序、数字精度与其它标量原样保留。 */
  private static JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.propertyNames().forEach(names::add);
      names.sort(Comparator.naturalOrder());
      ObjectNode sorted = NODES.objectNode();
      for (String name : names) {
        sorted.set(name, canonicalize(node.get(name)));
      }
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode sorted = NODES.arrayNode();
      for (JsonNode item : node) {
        sorted.add(canonicalize(item));
      }
      return sorted;
    }
    return node;
  }

  private static String digest(JsonNode node) {
    String canonical = JsonValues.write(canonicalize(node));
    return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
  }

  /** 按字典序冻结文本集合，使指纹与声明顺序无关。 */
  static List<String> sortedNames(Set<String> names) {
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(Comparator.naturalOrder());
    return sorted;
  }

  /** 供包内其它身份指纹复用的 authSpec 规范节点。 */
  static ObjectNode authSpecIdentityNode(PluginAuthSpec authSpec) {
    return authSpecNode(authSpec);
  }

  /** 供包内其它身份指纹复用的规范摘要：先递归按对象键排序，再取紧凑 UTF-8 输出的 SHA-256。 */
  static String digestOf(JsonNode node) {
    return digest(node);
  }
}
