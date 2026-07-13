package fun.fengwk.kkstudio.agent.tool;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;

/**
 * DefaultToolRegistry 的注册校验测试。
 *
 * @author fengwk
 */
public class DefaultToolRegistryTest {

  /** 校验注册后可按名称取得完整注册项。 */
  @Test
  public void testRegisterAndGet() {
    DefaultToolRegistry registry = new DefaultToolRegistry();
    Tool alpha = noopTool();
    ToolRegistration registration = new ToolRegistration("alpha", toolInfo("alpha"), alpha);

    registry.register(registration);

    assertSame(registration, registry.get("alpha"));
    assertSame(alpha, registry.get("alpha").getTool());
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
