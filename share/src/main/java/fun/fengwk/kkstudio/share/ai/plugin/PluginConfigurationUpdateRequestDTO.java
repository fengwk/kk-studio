package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

import java.util.Map;

/**
 * {@code PUT /api/plugins/{pluginId}/configuration} 请求体：期望坐标 + 普通值 + 秘密补丁。
 *
 * <p>两个期望坐标都不可省略：{@code expectedEpoch} 是全局 catalog 修订、{@code expectedRevision} 是该插件的配置修订，两者都是
 * canonical 非负十进制字符串（仓库既有的 Long wire 约定），不可解析即 400，任何不匹配都是 409。
 *
 * <p>{@code valuesJson} 是严格 JSON object 文本，保持调用方的数字精度并按 64 KiB UTF-8 预算校验；服务端只做 schema
 * 归一化与校验，不重新序列化。 {@code secretPatch} 的键必须是被 schema 声明的秘密字段；未知字段、非法操作或带值的 KEEP/CLEAR 都 fail
 * closed，省略即 KEEP。
 */
@Data
public class PluginConfigurationUpdateRequestDTO {

  /** 期望的全局 catalog epoch（canonical 非负十进制字符串，必填）。 */
  private String expectedEpoch;

  /** 期望的该插件配置修订（canonical 非负十进制字符串，必填）。 */
  private String expectedRevision;

  /** 严格 JSON object 的普通配置值原文；秘密绝不进入这里。 */
  private String valuesJson;

  /** 秘密字段补丁：字段名到单字段操作；省略的字段按 KEEP 处理。整个补丁不进入 {@code toString}。 */
  @ToString.Exclude private Map<String, PluginSecretPatchDTO> secretPatch;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown plugin configuration field: " + fieldName);
  }
}
