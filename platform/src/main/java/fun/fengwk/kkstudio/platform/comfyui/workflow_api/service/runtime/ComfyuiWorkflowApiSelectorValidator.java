package fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import org.springframework.util.Assert;

/**
 * 集中维护 defaultSelector 的最小静态校验规则，并使用 Jayway JsonPath compile 进一步验证语法可解析。运行期提交会按 Jackson -> JsonPath
 * 解析并取值。
 *
 * @author fengwk
 */
public final class ComfyuiWorkflowApiSelectorValidator {

  public static final int MAX_SELECTOR_LENGTH = 1024;

  private ComfyuiWorkflowApiSelectorValidator() {}

  /**
   * 对 defaultSelector 做静态校验，并通过 {@link JsonPath#compile(String)} 校验语法。
   *
   * @return trimmed 后的 selector
   * @throws IllegalArgumentException 任何静态规则失败或 JsonPath 编译失败
   */
  public static String validate(String selector) {
    String trimmed = selector == null ? null : selector.trim();
    if (trimmed == null || trimmed.isEmpty()) {
      return null;
    }
    Assert.isTrue(
        trimmed.length() <= MAX_SELECTOR_LENGTH,
        "selector must not exceed " + MAX_SELECTOR_LENGTH + " chars");
    // 安全：禁止片段以 .. 形式描述父级迭代，避免循环引用；拒绝过滤操作符 =~。
    if (trimmed.contains("..")) {
      throw new IllegalArgumentException("selector must not contain '..' descent operator");
    }
    if (trimmed.contains("=~")) {
      throw new IllegalArgumentException("selector must not contain '=~' regex filter operator");
    }
    try {
      JsonPath.compile(trimmed);
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("selector must compile as JsonPath: " + trimmed, e);
    }
    return trimmed;
  }
}
