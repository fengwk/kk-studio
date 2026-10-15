package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 连接检查 SPI 的契约测试：三个维度只有声明的取值，未声明能力的实例默认返回空 Optional。
 *
 * <p>这些断言同时锁定 plugin-api 的类级行覆盖门禁：新维度枚举与 {@link PluginInstance#connectionCheck()} 的默认实现都必须被真实执行。
 */
class PluginConnectionCheckResultTest {

  /** 测试意图：三个维度的全部合法取值与契约一致，浏览器登录会话当前只有唯一合法值。 */
  @Test
  void dimensionsCarryOnlyTheDeclaredValues() {
    assertEquals(
        Set.of("REACHABLE", "UNREACHABLE", "NOT_CHECKED", "INDETERMINATE"),
        Set.of(
            PluginConnectionCheckResult.Reachability.REACHABLE.name(),
            PluginConnectionCheckResult.Reachability.UNREACHABLE.name(),
            PluginConnectionCheckResult.Reachability.NOT_CHECKED.name(),
            PluginConnectionCheckResult.Reachability.INDETERMINATE.name()));
    assertEquals(
        Set.of("ACCEPTED", "REJECTED", "UNAVAILABLE", "NOT_CHECKED"),
        Set.of(
            PluginConnectionCheckResult.Authentication.ACCEPTED.name(),
            PluginConnectionCheckResult.Authentication.REJECTED.name(),
            PluginConnectionCheckResult.Authentication.UNAVAILABLE.name(),
            PluginConnectionCheckResult.Authentication.NOT_CHECKED.name()));
    assertEquals(
        List.of(PluginConnectionCheckResult.BrowserSession.UNKNOWN),
        List.of(PluginConnectionCheckResult.BrowserSession.values()));
  }

  /** 测试意图：结果按值相等，且维度只以类型化枚举暴露，没有自由文本。 */
  @Test
  void resultIsAValueWithTypedDimensions() {
    PluginConnectionCheckResult result =
        new PluginConnectionCheckResult(
            PluginConnectionCheckResult.Reachability.REACHABLE,
            PluginConnectionCheckResult.Authentication.ACCEPTED,
            PluginConnectionCheckResult.BrowserSession.UNKNOWN);

    assertEquals(
        new PluginConnectionCheckResult(
            PluginConnectionCheckResult.Reachability.REACHABLE,
            PluginConnectionCheckResult.Authentication.ACCEPTED,
            PluginConnectionCheckResult.BrowserSession.UNKNOWN),
        result);
    assertEquals(PluginConnectionCheckResult.Reachability.REACHABLE, result.reachability());
    assertEquals(PluginConnectionCheckResult.Authentication.ACCEPTED, result.authentication());
    assertEquals(PluginConnectionCheckResult.BrowserSession.UNKNOWN, result.browserSession());
  }

  /** 测试意图：任何维度缺失都是编程错误，绝不以默认值伪造结论。 */
  @Test
  void missingDimensionsAreRejected() {
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginConnectionCheckResult(
                null,
                PluginConnectionCheckResult.Authentication.NOT_CHECKED,
                PluginConnectionCheckResult.BrowserSession.UNKNOWN));
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginConnectionCheckResult(
                PluginConnectionCheckResult.Reachability.NOT_CHECKED,
                null,
                PluginConnectionCheckResult.BrowserSession.UNKNOWN));
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginConnectionCheckResult(
                PluginConnectionCheckResult.Reachability.NOT_CHECKED,
                PluginConnectionCheckResult.Authentication.NOT_CHECKED,
                null));
  }

  /** 测试意图：未声明能力的实例默认返回空 Optional，绝不隐含任何检查能力。 */
  @Test
  void instancesDeclareNoConnectionCheckByDefault() {
    PluginInstance instance =
        new PluginInstance() {
          @Override
          public List<HarnessContributor> harnessContributors() {
            return List.of();
          }

          @Override
          public List<CanvasFunctionAdapter> canvasAdapters() {
            return List.of();
          }

          @Override
          public void close() {}
        };

    assertEquals(Optional.empty(), instance.connectionCheck());
  }
}
