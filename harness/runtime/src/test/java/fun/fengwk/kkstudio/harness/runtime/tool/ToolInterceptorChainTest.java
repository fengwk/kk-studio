package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ToolInterceptorChainTest {
  private static final Path ROOT = Path.of("/tmp/tool-interceptor-chain");
  private static final PermissionPromptPreview PREVIEW =
      new PermissionPromptPreview("write", ".", "{}");

  /** 普通 modifier 的输入顺序与串行可见性决定最终调用，boundary 无论注册位置都只能最后观察。 */
  @Test
  void appliesBeforeInterceptorsInInputOrderWithSerialVisibility() {
    List<String> order = new ArrayList<>();
    PermissionBoundaryInterceptor boundary =
        boundary(
            context -> {
              order.add("permission:" + context.call().argumentsJson());
              return permission(context);
            });
    BeforeToolCallInterceptor first =
        interceptor(
            context -> {
              order.add("first:" + context.call().argumentsJson());
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
    BeforeToolCallInterceptor second =
        interceptor(
            context -> {
              order.add("second:" + context.call().argumentsJson());
              return new BeforeToolCallResult(context.binding(), context.call().argumentsJson());
            });
    List<BeforeToolCallInterceptor> supplied = new ArrayList<>(List.of(boundary, first, second));
    ToolInterceptorChain chain = new ToolInterceptorChain(supplied, List.of());
    supplied.clear();

    BeforeToolCallResult result =
        chain.before(
            ToolBinding.of(descriptor(baseSchema())),
            new ToolCall("call", "write", "{\"path\":\"a.txt\"}"),
            ToolSettings.DEFAULT,
            false,
            ROOT,
            ROOT);

    assertEquals(
        List.of(
            "first:{\"path\":\"a.txt\"}",
            "second:{\"path\":\"a.txt\",\"content\":\"ok\"}",
            "permission:{\"path\":\"a.txt\",\"content\":\"ok\"}"),
        order);
    assertEquals("1", result.binding().descriptor().version());
    assertTrue(result.argumentsJson().contains("content"));
    assertEquals(PermissionAction.ALLOW, result.permissionAction());
  }

  /** 每个 modifier 输出立即做 schema 校验，后续 hook 不能修复一个曾经非法的中间调用。 */
  @Test
  void validatesSchemaAfterEveryBeforeInterceptor() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");
    List<String> order = new ArrayList<>();

    ToolInterceptorException failure =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        List.of(
                            interceptor(
                                context -> new BeforeToolCallResult(context.binding(), "{}")),
                            interceptor(
                                context -> {
                                  order.add("fixer");
                                  return new BeforeToolCallResult(
                                      context.binding(), "{\"path\":\"fixed\"}");
                                }),
                            boundary(ToolInterceptorChainTest::permission)),
                        List.of())
                    .before(binding, call, ToolSettings.DEFAULT, false, ROOT, ROOT));

    assertTrue(failure.getMessage().contains("schema validation"));
    assertTrue(order.isEmpty());
  }

  /** 工具身份、异常包装、permission 来源与 boundary 不可修改最终调用共同封闭 before seam。 */
  @Test
  void rejectsInvalidBeforeContracts() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");

    assertBeforeFailure(
        binding,
        call,
        interceptor(
            context ->
                new BeforeToolCallResult(
                    ToolBinding.of(descriptor("read", baseSchema())),
                    context.call().argumentsJson())),
        "must not rename");
    assertBeforeFailure(
        binding,
        call,
        interceptor(
            context ->
                new BeforeToolCallResult(
                    context.binding(),
                    context.call().argumentsJson(),
                    PermissionAction.ALLOW,
                    PREVIEW)),
        "must not produce permission");
    assertBeforeFailure(
        binding,
        call,
        boundary(
            context ->
                new BeforeToolCallResult(
                    context.binding(), "{\"path\":\"changed\"}", PermissionAction.ALLOW, PREVIEW)),
        "must not change");
    assertBeforeFailure(
        binding,
        call,
        boundary(
            context -> new BeforeToolCallResult(context.binding(), context.call().argumentsJson())),
        "must produce permission");

    ToolInterceptorException failed =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        List.of(
                            interceptor(
                                context -> {
                                  throw new IllegalStateException("boom");
                                }),
                            boundary(ToolInterceptorChainTest::permission)),
                        List.of())
                    .before(binding, call, ToolSettings.DEFAULT, false, ROOT, ROOT));
    assertTrue(failed.getMessage().contains("beforeToolCall interceptor failed"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BeforeToolCallResult(binding, call.argumentsJson(), PermissionAction.ALLOW, null));
  }

  /** 无 boundary 的链可服务 after-only worker，但 preparation 可检测缺失，重复 boundary 则构造即失败。 */
  @Test
  void allowsNoBoundaryButRejectsDuplicateBoundaries() {
    ToolInterceptorChain afterOnly =
        new ToolInterceptorChain(List.of(), List.of(context -> context.result()));

    assertFalse(afterOnly.hasPermissionBoundary());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInterceptorChain(
                List.of(
                    boundary(ToolInterceptorChainTest::permission),
                    boundary(ToolInterceptorChainTest::permission)),
                List.of()));
  }

  /** Hook 共享的 settings/path 快照必须非空，路径统一为绝对规范形式以避免各扩展自行解释。 */
  @Test
  void normalizesFrozenBeforeContext() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");
    BeforeToolCallContext context =
        new BeforeToolCallContext(
            binding, call, ToolSettings.DEFAULT, true, Path.of("work", ".."), Path.of("."));

    assertTrue(context.workdir().isAbsolute());
    assertEquals(context.workdir(), context.workdir().normalize());
    assertTrue(context.environmentRoot().isAbsolute());
    assertThrows(
        NullPointerException.class,
        () -> new BeforeToolCallContext(binding, call, null, false, ROOT, ROOT));
  }

  /** afterToolCall 只按输入顺序串行转换，且任何 hook 都不能改变 toolCallId。 */
  @Test
  void appliesAfterInterceptorsAndProtectsToolCallIdentity() {
    ToolBinding binding = ToolBinding.of(descriptor(baseSchema()));
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"a.txt\"}");
    List<String> order = new ArrayList<>();
    AfterToolCallInterceptor late =
        afterInterceptor(
            context -> {
              order.add("late");
              return context.result();
            });
    AfterToolCallInterceptor early =
        afterInterceptor(
            context -> {
              order.add("early");
              return context.result();
            });
    List<AfterToolCallInterceptor> supplied = new ArrayList<>(List.of(late, early));
    ToolInterceptorChain chain = new ToolInterceptorChain(List.of(), supplied);
    supplied.clear();

    ToolResult result =
        chain.after(new AfterToolCallContext(1L, binding, call, ToolResult.error("call", "error")));
    assertEquals(List.of("late", "early"), order);
    assertEquals("call", result.toolCallId());

    assertThrows(
        ToolInterceptorException.class,
        () ->
            new ToolInterceptorChain(
                    List.of(),
                    List.of(afterInterceptor(context -> ToolResult.error("different", "changed"))))
                .after(new AfterToolCallContext(1L, binding, call, result)));
  }

  private static void assertBeforeFailure(
      ToolBinding binding,
      ToolCall call,
      BeforeToolCallInterceptor interceptor,
      String expectedMessage) {
    ToolInterceptorException failure =
        assertThrows(
            ToolInterceptorException.class,
            () ->
                new ToolInterceptorChain(
                        interceptor instanceof PermissionBoundaryInterceptor
                            ? List.of(interceptor)
                            : List.of(interceptor, boundary(ToolInterceptorChainTest::permission)),
                        List.of())
                    .before(binding, call, ToolSettings.DEFAULT, false, ROOT, ROOT));
    assertTrue(failure.getMessage().contains(expectedMessage));
  }

  private static BeforeToolCallResult permission(BeforeToolCallContext context) {
    return new BeforeToolCallResult(
        context.binding(), context.call().argumentsJson(), PermissionAction.ALLOW, PREVIEW);
  }

  private static BeforeToolCallInterceptor interceptor(BeforeFunction function) {
    return function::apply;
  }

  private static PermissionBoundaryInterceptor boundary(BeforeFunction function) {
    return function::apply;
  }

  private static AfterToolCallInterceptor afterInterceptor(AfterFunction function) {
    return function::apply;
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
