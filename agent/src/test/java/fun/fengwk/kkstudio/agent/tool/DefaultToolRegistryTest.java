package fun.fengwk.kkstudio.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;

import java.util.List;

/**
 * DefaultToolRegistry 的注册校验测试。
 *
 * @author fengwk
 */
public class DefaultToolRegistryTest {

  /** 校验注册表按字典序返回工具名，并通过同一注册项提供描述与实现。 */
  @Test
  public void testRegisterAndListToolNamesInStableOrder() {
    DefaultToolRegistry registry = new DefaultToolRegistry();
    Tool alpha = noopTool();
    Tool beta = noopTool();

    registry.register(new ToolRegistration("beta", toolInfo("beta"), beta));
    registry.register(new ToolRegistration("alpha", toolInfo("alpha"), alpha));

    assertEquals(List.of("alpha", "beta"), registry.listToolNames());
    assertSame(alpha, registry.getTool("alpha"));
    assertEquals("beta", registry.getToolInfo("beta").getName());
    assertEquals(
        List.of("alpha", "beta"),
        registry.listRegistrations().stream().map(ToolRegistration::getName).toList());
  }

  /** 校验重复注册、名称不一致与负超时都会 fail-fast。 */
  @Test
  public void testRegisterRejectsInvalidRegistration() {
    DefaultToolRegistry registry = new DefaultToolRegistry();
    registry.register(new ToolRegistration("echo", toolInfo("echo"), noopTool()));

    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("echo", toolInfo("echo"), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("a", toolInfo("b"), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.register(new ToolRegistration("bad", toolInfo("bad"), negativeTimeoutTool())));
  }

  /** 校验注册项缺失核心字段时 fail-fast。 */
  @Test
  public void testRegisterRejectsMissingFields() {
    DefaultToolRegistry registry = new DefaultToolRegistry();

    assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration(null, toolInfo("echo"), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration(" ", toolInfo("echo"), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("echo", null, noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("echo", toolInfo(null), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("echo", toolInfo(" "), noopTool())));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(new ToolRegistration("echo", toolInfo("echo"), null)));
  }

  /** 校验缺失工具查询返回 null，便于调用方生成稳定 tool-not-found 事件。 */
  @Test
  public void testGetMissingToolReturnsNull() {
    DefaultToolRegistry registry = new DefaultToolRegistry();

    assertNull(registry.get(null));
    assertNull(registry.get("missing"));
    assertNull(registry.getToolInfo("missing"));
    assertNull(registry.getTool("missing"));
  }

  private ToolInfo toolInfo(String name) {
    return ToolInfo.builder()
        .name(name)
        .description(name)
        .inputSchema(ToolParamsSchema.builder().build())
        .build();
  }

  private Tool noopTool() {
    return (request, handler) -> new NoopToolExecutionHandle();
  }

  private Tool negativeTimeoutTool() {
    return new Tool() {
      @Override
      public ToolExecutionHandle asyncExecute(
          ToolCallRequest request, ToolExecutionHandler handler) {
        return new NoopToolExecutionHandle();
      }

      @Override
      public long timeoutSeconds() {
        return -1L;
      }
    };
  }
}
