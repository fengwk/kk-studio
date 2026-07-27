package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class ToolFactoriesTest {

  @Test
  void rejectsDuplicateKeyOnConstruction() {
    ToolDescriptor d = descriptor("dup", "1");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolFactories(
                    List.of(ToolFactory.singleton(tool(d)), ToolFactory.singleton(tool(d)))));
    assertTrue(error.getMessage().contains("duplicate ToolFactory"));
    assertTrue(error.getMessage().contains("dup@1"));
  }

  @Test
  void descriptorsAreReturnedInRegistrationOrder() {
    ToolFactory a = ToolFactory.singleton(tool(descriptor("a", "1")));
    ToolFactory b = ToolFactory.singleton(tool(descriptor("b", "1")));
    ToolFactories factories = new ToolFactories(List.of(a, b));
    assertEquals(
        List.of("a", "b"), factories.descriptors().stream().map(ToolDescriptor::name).toList());
  }

  @Test
  void findReturnsSameToolForRegisteredKey() {
    Tool tool = tool(descriptor("goal", "1"));
    ToolFactories factories = new ToolFactories(List.of(ToolFactory.singleton(tool)));
    assertSame(tool, factories.find("goal", "1").orElseThrow());
  }

  @Test
  void findReturnsEmptyForUnknownKey() {
    ToolFactories factories =
        new ToolFactories(List.of(ToolFactory.singleton(tool(descriptor("goal", "1")))));
    assertEquals(Optional.empty(), factories.find("missing", "1"));
  }

  @Test
  void findRejectsDescriptorMismatchAtRuntime() {
    ToolDescriptor declared = descriptor("mismatch", "1");
    ToolDescriptor actual = descriptor("mismatch", "2");
    ToolFactory factory =
        new ToolFactory() {
          @Override
          public ToolDescriptor descriptor() {
            return declared;
          }

          @Override
          public Tool create() {
            return tool(actual);
          }
        };
    ToolFactories factories = new ToolFactories(List.of(factory));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> factories.find("mismatch", "1"));
    assertTrue(error.getMessage().contains("does not match registered"));
  }

  @Test
  void singletonFactoryRejectsDescriptorDrift() {
    AtomicReference<ToolDescriptor> descriptor = new AtomicReference<>(descriptor("fixed", "1"));
    Tool tool =
        new Tool() {
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
    ToolFactory factory = ToolFactory.singleton(tool);

    assertSame(tool, factory.create());
    descriptor.set(descriptor("changed", "1"));

    IllegalStateException error = assertThrows(IllegalStateException.class, factory::create);
    assertTrue(error.getMessage().contains("does not match frozen"));
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static Tool tool(ToolDescriptor descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
