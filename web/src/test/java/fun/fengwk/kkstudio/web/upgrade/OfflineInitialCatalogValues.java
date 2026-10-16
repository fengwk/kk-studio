package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 基于真实安装默认值构造测试输入，并提供启用 H3、DIRECT 和 OpenCLI 的合成配置；不含秘密值。 */
final class OfflineInitialCatalogValues {

  /** OpenCLI Seedance 的合成工作区标识：只为让工作区校验通过，不是任何真实部署值。 */
  static final String SEEDANCE_WORKSPACE_ID = "offline-synthetic-workspace";

  /** 追加到默认 H3 预置之后的合法小 DIRECT 工作流 id（即它的 Canvas Function 名）。 */
  static final String DIRECT_WORKFLOW_ID = "offline-direct-video";

  /** 默认 H3 预置工作流的 Canvas Function 名。 */
  static final String H3_WORKFLOW_ID = "minimax-h3-ref2va";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** 打开上述能力后必须处于 enabled 态的 Canvas Function 名：媒体裁剪、默认 H3 预置、追加的 DIRECT、GPT Image 2 与四个 Seedance。 */
  static final Set<String> EXPECTED_ENABLED_CANVAS_FUNCTIONS =
      Set.of(
          "image.crop",
          H3_WORKFLOW_ID,
          DIRECT_WORKFLOW_ID,
          "gpt-image-2",
          "seedance2.0",
          "seedance2.0fast",
          "seedance2.0_vip",
          "seedance2.0fast_vip");

  private OfflineInitialCatalogValues() {}

  /** 快照里真正处于 enabled 态的 Canvas Function 名集合；停用的定义也会注册，必须按 enabled 过滤。 */
  static Set<String> enabledCanvasFunctions(PluginCatalogSnapshot snapshot) {
    return snapshot.canvasFunctionCatalog().list().stream()
        .filter(CanvasFunctionCatalog.RegisteredFunction::enabled)
        .map(registered -> registered.function().name())
        .collect(Collectors.toSet());
  }

  /** 每个已安装 id 的初始普通值：当前发行物该 Plugin 自己声明的默认值。 */
  static Map<String, String> valuesJsonByPluginId(StudioPluginRegistry registry) {
    Map<String, String> values = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      values.put(plugin.descriptor().pluginId(), plugin.configurationSchema().defaultsJson());
    }
    return Map.copyOf(values);
  }

  /** 每个已安装 id 的「已录入」秘密字段名：离线装配从不解密也不录入凭据，因此全部为空。 */
  static Map<String, List<String>> noConfiguredSecretFields(StudioPluginRegistry registry) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      fields.put(plugin.descriptor().pluginId(), List.of());
    }
    return Map.copyOf(fields);
  }

  /** 在真实 defaults 之上打开 ComfyUI 与 OpenCLI 的声明能力；其余 Plugin 保持自己的默认值。 */
  static Map<String, String> valuesJsonWithEnabledCapabilities(StudioPluginRegistry registry) {
    Map<String, String> values = new LinkedHashMap<>(valuesJsonByPluginId(registry));
    values.computeIfPresent("comfyui", (pluginId, defaults) -> enableComfyui(defaults));
    values.computeIfPresent("opencli", (pluginId, defaults) -> enableOpenCli(defaults));
    return Map.copyOf(values);
  }

  /** ComfyUI：启用后保留默认 H3 预置，并追加一个合法小 DIRECT 工作流。 */
  private static String enableComfyui(String defaultsJson) {
    ObjectNode settings = (ObjectNode) MAPPER.readTree(defaultsJson);
    settings.put("enabled", true);
    ArrayNode workflows = (ArrayNode) settings.get("workflows");
    workflows.add(directWorkflow());
    return MAPPER.writeValueAsString(settings);
  }

  private static ObjectNode directWorkflow() {
    try (InputStream input =
        Objects.requireNonNull(
            OfflineInitialCatalogValues.class.getResourceAsStream("offline-direct-workflow.json"),
            "offline DIRECT workflow fixture must exist")) {
      return (ObjectNode) MAPPER.readTree(input);
    } catch (IOException error) {
      throw new UncheckedIOException("could not close the offline workflow fixture", error);
    }
  }

  /** OpenCLI：启用并打开 GPT Image 2 与 Seedance；Seedance 需要一个合成 workspace 才能启用。 */
  private static String enableOpenCli(String defaultsJson) {
    ObjectNode settings = (ObjectNode) MAPPER.readTree(defaultsJson);
    settings.put("enabled", true);
    settings.put("baseUrl", "http://offline.invalid");
    ((ObjectNode) settings.get("gptImage2")).put("paidEnabled", true);
    ObjectNode seedance = (ObjectNode) settings.get("seedance");
    seedance.put("enabled", true);
    seedance.put("workspaceId", SEEDANCE_WORKSPACE_ID);
    return MAPPER.writeValueAsString(settings);
  }
}
