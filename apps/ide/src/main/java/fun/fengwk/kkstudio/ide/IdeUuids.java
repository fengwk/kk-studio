package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.UUID;

/** IDE 边界的 canonical UUID 解析：客户端入参统一映射为环境资源校验失败。 */
public final class IdeUuids {

  private IdeUuids() {}

  /** 解析客户端入参中的 canonical UUID；缺失或非规范形态都以该字段的校验错误拒绝。 */
  public static UUID requireInput(String value, String field) {
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("environment", field + " must be a canonical UUID");
    }
  }
}
