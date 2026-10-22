package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Project workflow 内的自然状态编码。
 *
 * <p>编码是项目内唯一的自然字符串，匹配 {@code [A-Z][A-Z0-9_]{0,63}}：INIT/BLOCKED/DONE 为固定保留编码， 其余为工作阶段。编码一旦被历史
 * Issue 使用就只可停用、不可删除或改码，因此这里不做大小写折叠、别名或归一化。
 */
public record ProjectStateCode(String value) {

  private static final Pattern PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public ProjectStateCode {
    Objects.requireNonNull(value, "value");
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("state code must match [A-Z][A-Z0-9_]{0,63}: " + value);
    }
  }

  /** 由自然编码构造状态编码。 */
  public static ProjectStateCode of(String value) {
    return new ProjectStateCode(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
