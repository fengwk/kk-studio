package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * {@code PUT /api/plugins/{pluginId}/configuration} 请求体：期望坐标 + 普通值 + 秘密补丁。
 *
 * <p>{@code expectedRevision} 是该插件的当前配置修订，canonical 非负十进制字符串，不可解析即 400，不匹配即 409。
 *
 * <p>{@code valuesJson} 是严格 JSON object 文本，保持调用方的数字精度并按 64 KiB UTF-8 预算校验；服务端只做 schema
 * 归一化与校验，不重新序列化。 {@code secretPatch} 的键必须是被 schema 声明的秘密字段；未知字段、非法操作或带值的 KEEP/CLEAR 都 fail
 * closed，省略即 KEEP。
 */
@Data
public class PluginConfigurationUpdateRequestDTO {

  /** 期望的该插件配置修订（canonical 非负十进制字符串，必填）；数字/布尔/null 一律拒绝，绝不做标量强制转换。 */
  @JsonDeserialize(using = PluginWireTextDeserializer.class)
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
