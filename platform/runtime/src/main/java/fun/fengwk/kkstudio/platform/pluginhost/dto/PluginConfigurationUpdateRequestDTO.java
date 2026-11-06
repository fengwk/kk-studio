package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.Map;

/**
 * {@code PUT /api/platform/plugins/{pluginId}/configuration} 请求体：期望版本 + 普通值 + 秘密补丁。
 *
 * <p>{@code expectedVersion} 是该插件的当前配置版本，canonical 非负十进制字符串，不匹配即 409。{@code values} 的结构由 Plugin
 * schema 决定，服务端只做 schema 归一化与校验。{@code secretPatch} 的键必须是被 schema 声明的秘密字段，省略即 KEEP。
 */
@Data
public class PluginConfigurationUpdateRequestDTO {

  /** 期望的该插件配置版本（canonical 非负十进制字符串）。 */
  private String expectedVersion;

  /** 普通配置值；秘密绝不进入这里。 */
  private JsonNode values;

  /** 秘密字段补丁：字段名到单字段操作；省略的字段按 KEEP 处理。 */
  @ToString.Exclude private @Nullable Map<String, SecretUpdate> secretPatch;
}
