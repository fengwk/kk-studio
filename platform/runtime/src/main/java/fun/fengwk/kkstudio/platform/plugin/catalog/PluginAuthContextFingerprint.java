package fun.fengwk.kkstudio.platform.plugin.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;

import java.util.Objects;

/**
 * 认证上下文指纹：同一 Plugin 在同一 code/schema 身份下，「决定认证目标与权限的普通配置字段」的严格规范值的确定性摘要。
 *
 * <p>派生输入是 plugin id、plugin code version、{@link PluginCatalogFingerprint#schema} 身份（含 authSpec 与
 * secrets 声明）以及 {@link PluginAuthSpec#contextFields()} 对应的已归一化顶层字段值。因此：
 *
 * <ul>
 *   <li>目标/权限字段值变化推进上下文指纹，进而推进认证代际；
 *   <li>{@code enabled}、纯 timeout 等不属于 contextFields 的字段变化不改变指纹；
 *   <li>集合顺序、对象键顺序与 DB jsonb 回读后的键顺序都不改变结果（复用目录指纹的规范化与 SHA-256）。
 * </ul>
 *
 * <p>指纹不是秘密：它只描述配置坐标，不携带凭据值。
 */
public final class PluginAuthContextFingerprint {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private PluginAuthContextFingerprint() {}

  /**
   * 派生认证上下文指纹。
   *
   * @param pluginId Plugin 安装身份
   * @param codeVersion 当前 Plugin code version
   * @param schemaFingerprint 当前配置定义指纹（含 authSpec 与 secrets 声明）
   * @param authSpec 当前安装期认证声明
   * @param resolvedValues 已按 schema 归一化的普通配置对象
   */
  public static String derive(
      String pluginId,
      String codeVersion,
      String schemaFingerprint,
      PluginAuthSpec authSpec,
      JsonNode resolvedValues) {
    Objects.requireNonNull(pluginId, "pluginId");
    Objects.requireNonNull(codeVersion, "codeVersion");
    Objects.requireNonNull(schemaFingerprint, "schemaFingerprint");
    Objects.requireNonNull(authSpec, "authSpec");
    Objects.requireNonNull(resolvedValues, "resolvedValues");
    if (!resolvedValues.isObject()) {
      throw new IllegalArgumentException("resolvedValues must be a JSON object");
    }
    ObjectNode root = NODES.objectNode();
    root.put("kind", "plugin-auth-context");
    root.put("pluginId", pluginId);
    root.put("pluginCodeVersion", codeVersion);
    root.put("schemaFingerprint", schemaFingerprint);
    root.set("authSpec", PluginCatalogFingerprint.authSpecIdentityNode(authSpec));
    ObjectNode context = root.putObject("contextFields");
    for (String field : PluginCatalogFingerprint.sortedNames(authSpec.contextFields())) {
      JsonNode value = resolvedValues.get(field);
      context.set(field, value == null ? NODES.nullNode() : value);
    }
    return PluginCatalogFingerprint.digestOf(root);
  }
}
