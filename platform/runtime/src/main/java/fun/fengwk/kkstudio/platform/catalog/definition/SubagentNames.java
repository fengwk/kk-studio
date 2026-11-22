package fun.fengwk.kkstudio.platform.catalog.definition;

/**
 * subagent 名的长度与字符集：Agent 配置 codec 与目录校验共用的唯一实现。
 *
 * <p>subagent 名直接指向另一个 Agent 名，因此与 Agent 名同界：不超过 {@value #MAX_LENGTH} 字符、无首尾空白、不含 {@code :}、{@code
 * /}、{@code @}、{@code \}。两处各写一份会漂移出「codec 放行、目录拒绝」的旁路。
 */
public final class SubagentNames {

  private static final int MAX_LENGTH = 64;

  private SubagentNames() {}

  /** 校验单个 subagent 名；不合规抛 {@link IllegalArgumentException}，分类由调用方按各自的错误契约决定。 */
  public static void require(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("subagent name must not be blank");
    }
    if (!name.equals(name.trim())) {
      throw new IllegalArgumentException("subagent name must not contain surrounding whitespace");
    }
    if (name.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "subagent name must be <= " + MAX_LENGTH + " characters: " + name);
    }
    if (name.indexOf(':') >= 0
        || name.indexOf('/') >= 0
        || name.indexOf('@') >= 0
        || name.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          "subagent name must not contain ':', '/', '@' or '\\': " + name);
    }
  }
}
