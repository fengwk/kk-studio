package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

/**
 * 编辑技能包的描述与 Git 渠道设置；上传渠道只能改描述。
 *
 * <p>{@code branch} 对 Git 渠道必填、对上传渠道必须省略；{@code token} 省略或 KEEP 保留、CLEAR 清除、SET 替换。上传渠道传入任一 Git
 * 字段都会被拒绝。
 */
@Data
public class SkillPackageEditDTO {

  /** 客户端已展示的 CAS 版本号（十进制字符串）。 */
  private String expectedVersion;

  /** 可空包描述；null 表示清空。 */
  private @Nullable String description;

  /** Git 渠道的检查分支；上传渠道必须省略。 */
  private @Nullable String branch;

  /** Git 渠道私有仓库令牌；省略或 KEEP 保留、CLEAR 清除、SET 替换，绝不回显。 */
  private @Nullable SecretUpdate token;
}
