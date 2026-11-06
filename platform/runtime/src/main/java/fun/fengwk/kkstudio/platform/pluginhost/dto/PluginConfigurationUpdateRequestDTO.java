package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.Map;

/**
 * {@code PUT /api/platform/plugins/{pluginId}/configuration} 请求体：期望版本 + 普通值原文 + 秘密补丁。
 *
 * <p>{@code expectedVersion} 是该插件的当前配置版本，canonical 非负十进制字符串，不匹配即 409。{@code valuesJson} 是用户编辑的 严格
 * JSON object 原文，按提交文本逐字节保留大整数、精确小数与超范围指数；64 KiB UTF-8 预算也按提交原文计。服务端只做 schema 归一化与校验。{@code
 * secretPatch} 的键必须是被 schema 声明的秘密字段，省略即 KEEP。
 */
@Data
public class PluginConfigurationUpdateRequestDTO {

  /** 期望的该插件配置版本（canonical 非负十进制字符串）。 */
  private String expectedVersion;

  /** 普通配置值原文；秘密绝不进入这里。 */
  private String valuesJson;

  /** 秘密字段补丁：字段名到单字段操作；省略的字段按 KEEP 处理。 */
  @ToString.Exclude private @Nullable Map<String, SecretUpdate> secretPatch;
}
