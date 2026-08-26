package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** {@link ToolFactory} 的 Host identity、冻结定义与 descriptor 漂移校验测试。 */
class ToolFactoryTest {

  private static final AgentToolId ID = new AgentToolId("test.explicit-tool");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "1",
          ToolType.PLATFORM,
          "read tool",
          "read",
          new ToolParamsSchema("", Map.of(), Set.of(), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  /** 验证 singleton 使用调用方给出的稳定 ID，并固定为 HOST backend。 */
  @Test
  void singletonFreezesExplicitHostDefinition() {
    Tool tool = tool(new AtomicReference<>(DESCRIPTOR));

    ToolFactory factory = ToolFactory.singleton(ID, tool, ToolVisibility.INTERNAL, 7);

    assertEquals(
        new AgentToolDefinition(ID, DESCRIPTOR, ToolVisibility.INTERNAL, AgentToolBackend.HOST),
        factory.definition());
    assertEquals(7, factory.priority());
    assertSame(tool, factory.create());
  }

  /** 验证 create 比较完整 descriptor，而不是只比较 model-visible name/version。 */
  @Test
  void singletonRejectsAnyDescriptorDriftAtCreate() {
    AtomicReference<ToolDescriptor> descriptor = new AtomicReference<>(DESCRIPTOR);
    ToolFactory factory = ToolFactory.singleton(ID, tool(descriptor), ToolVisibility.SELECTABLE);

    descriptor.set(
        new ToolDescriptor(
            DESCRIPTOR.name(),
            DESCRIPTOR.version(),
            DESCRIPTOR.type(),
            "changed description",
            DESCRIPTOR.rendererKey(),
            DESCRIPTOR.inputSchema(),
            DESCRIPTOR.sideEffect(),
            DESCRIPTOR.timeout()));
    assertThrows(IllegalStateException.class, factory::create);
  }

  private static Tool tool(AtomicReference<ToolDescriptor> descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor.get();
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
