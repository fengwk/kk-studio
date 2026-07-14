package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolInterceptorChainTest {

  /** priority 小者先执行；同 priority 保持注册顺序，并对修改后 descriptor/参数重新校验。 */
  @Test
  void appliesBeforeInterceptorsInStablePriorityOrderAndRevalidates() {
    List<String> order = new ArrayList<>();
    BeforeToolCallInterceptor samePriorityFirst =
        interceptor(
            10,
            context -> {
              order.add("first");
              return new BeforeToolCallResult(context.binding(), context.call().argumentsJson());
            });
    BeforeToolCallInterceptor earlier =
        interceptor(
            1,
            context -> {
              order.add("earlier");
              ToolDescriptor changed =
                  descriptor(
                      new ToolParamsSchema(
                          "",
                          Map.of(
                              "path", new ToolStringSchema("path"),
                              "content", new ToolStringSchema("content")),
                          Set.of("path", "content"),
                          false));
              return new BeforeToolCallResult(
                  ToolBinding.of(changed), "{\"path\":\"a.txt\",\"content\":\"ok\"}");
            });
    BeforeToolCallInterceptor samePrioritySecond =
        interceptor(
            10,
            context -> {
              order.add("second");
              return new BeforeToolCallResult(context.binding(), context.call().argumentsJson());
            });
    ToolInterceptorChain chain =
        new ToolInterceptorChain(
            List.of(samePriorityFirst, earlier, samePrioritySecond), List.of());

    BeforeToolCallContext result =
        chain.before(
            ToolBinding.of(descriptor(baseSchema())),
            new ToolCall("call", "write", "{\"path\":\"a.txt\"}"));

    assertEquals(List.of("earlier", "first", "second"), order);
    assertEquals("1", result.binding().descriptor().version());
    assertTrue(result.call().argumentsJson().contains("content"));
  }

  /** interceptor 不得重命名工具，坏 schema 和实现异常都包装为明确 phase 错误。 */
  @Test
  void rejectsInvalidBeforeInterceptorOutputAndWrapsFailures() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");

    ToolInterceptorException renamed =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        List.of(
                            interceptor(
                                0,
                                context ->
                                    new BeforeToolCallResult(
                                        ToolBinding.of(descriptor("read", baseSchema())),
                                        context.call().argumentsJson()))),
                        List.of())
                    .before(binding, call));
    assertTrue(renamed.getMessage().contains("must not rename"));

    ToolInterceptorException invalid =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        List.of(
                            interceptor(
                                0,
                                context ->
                                    new BeforeToolCallResult(
                                        context.binding(), "{\"unknown\":true}"))),
                        List.of())
                    .before(binding, call));
    assertTrue(invalid.getMessage().contains("schema validation"));

    assertThrows(
        ToolInterceptorException.class,
        () ->
            new ToolInterceptorChain(
                    List.of(
                        interceptor(
                            0, context -> new BeforeToolCallResult(context.binding(), " "))),
                    List.of())
                .before(binding, call));

    ToolInterceptorException failed =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        List.of(
                            interceptor(
                                0,
                                context -> {
                                  throw new IllegalStateException("boom");
                                })),
                        List.of())
                    .before(binding, call));
    assertTrue(failed.getMessage().contains("beforeToolCall interceptor failed"));
  }

  /** afterToolCall 同样稳定执行，且不能改变 toolCallId。 */
  @Test
  void appliesAfterInterceptorsAndProtectsToolCallIdentity() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");
    List<String> order = new ArrayList<>();
    AfterToolCallInterceptor late =
        afterInterceptor(
            5,
            context -> {
              order.add("late");
              return context.result();
            });
    AfterToolCallInterceptor early =
        afterInterceptor(
            1,
            context -> {
              order.add("early");
              return context.result();
            });
    ToolInterceptorChain chain = new ToolInterceptorChain(List.of(), List.of(late, early));

    ToolResult result =
        chain.after(new AfterToolCallContext(1L, binding, call, ToolResult.error("call", "error")));
    assertEquals(List.of("early", "late"), order);
    assertEquals("call", result.toolCallId());

    assertThrows(
        ToolInterceptorException.class,
        () ->
            new ToolInterceptorChain(
                    List.of(),
                    List.of(
                        afterInterceptor(0, context -> ToolResult.error("different", "changed"))))
                .after(new AfterToolCallContext(1L, binding, call, result)));
  }

  private static BeforeToolCallInterceptor interceptor(int priority, BeforeFunction function) {
    return new BeforeToolCallInterceptor() {
      @Override
      public int priority() {
        return priority;
      }

      @Override
      public BeforeToolCallResult intercept(BeforeToolCallContext context) {
        return function.apply(context);
      }
    };
  }

  private static AfterToolCallInterceptor afterInterceptor(int priority, AfterFunction function) {
    return new AfterToolCallInterceptor() {
      @Override
      public int priority() {
        return priority;
      }

      @Override
      public ToolResult intercept(AfterToolCallContext context) {
        return function.apply(context);
      }
    };
  }

  private static ToolDescriptor descriptor(ToolParamsSchema schema) {
    return descriptor("write", schema);
  }

  private static ToolDescriptor descriptor(String name, ToolParamsSchema schema) {
    return new ToolDescriptor(
        name,
        "1",
        name,
        null,
        schema,
        ToolExecutionMode.CLOUD,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(30));
  }

  private static ToolParamsSchema baseSchema() {
    return new ToolParamsSchema(
        "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false);
  }

  @FunctionalInterface
  private interface BeforeFunction {
    BeforeToolCallResult apply(BeforeToolCallContext context);
  }

  @FunctionalInterface
  private interface AfterFunction {
    ToolResult apply(AfterToolCallContext context);
  }
}
