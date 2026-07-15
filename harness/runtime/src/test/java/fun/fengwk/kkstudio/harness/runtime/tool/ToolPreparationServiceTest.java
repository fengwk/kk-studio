package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ToolPreparationServiceTest {
  private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicLong ids = new AtomicLong(100);

  /** allow/ask/deny 一次性评估并保留 source ordinal；deny 只终止该 Invocation。 */
  @Test
  void preparesAllowAskAndDenyInSourceOrder() {
    ToolPreparationService service = service(new ToolInterceptorChain(List.of(), List.of()));
    List<ToolBinding> bindings = List.of(binding("read"), binding("write"), binding("delete"));
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "read", List.of(new PermissionRule("*", PermissionAction.ALLOW)),
                "write", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "delete", List.of(new PermissionRule("*", PermissionAction.DENY))),
            false);

    List<PreparedToolInvocation> prepared =
        service.prepare(
            List.of(
                new ToolCall("call-r", "read", "{\"path\":\"README.md\"}"),
                new ToolCall("call-w", "write", "{\"path\":\"notes.txt\"}"),
                new ToolCall("call-d", "delete", "{\"path\":\"secret.txt\"}")),
            bindings,
            settings,
            false,
            Path.of("/tmp/workspace"),
            Path.of("/tmp/workspace"),
            NOW);

    assertEquals(List.of(0, 1, 2), prepared.stream().map(PreparedToolInvocation::ordinal).toList());
    assertEquals(
        List.of(
            ToolInvocationStatus.QUEUED,
            ToolInvocationStatus.WAITING_APPROVAL,
            ToolInvocationStatus.FAILED),
        prepared.stream().map(PreparedToolInvocation::initialStatus).toList());
    assertNotNull(prepared.get(2).resultJson());
    assertTrue(prepared.get(2).resultJson().contains("Permission denied for delete."));
    assertFalse(prepared.get(2).resultJson().contains("\"terminate\""));
    assertEquals(NOW.plusSeconds(30), prepared.get(0).deadlineAt());
    assertEquals("1", prepared.get(0).binding().descriptor().version());
    assertEquals(ToolTargetType.CLOUD, prepared.get(0).binding().targetType());
  }

  /** YOLO 仅绕过 permission，不能绕过 schema、path surface 或 binding 校验。 */
  @Test
  void yoloBypassesPermissionButNotSchemaOrBinding() throws Exception {
    ToolPreparationService service = service(new ToolInterceptorChain(List.of(), List.of()));
    ToolSettings deny =
        new ToolSettings(
            Map.of("write", List.of(new PermissionRule("*", PermissionAction.DENY))), false);

    PreparedToolInvocation yolo =
        service
            .prepare(
                List.of(new ToolCall("call", "write", "{\"path\":\"notes.txt\"}")),
                List.of(binding("write")),
                deny,
                true,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW)
            .get(0);
    assertEquals(PermissionAction.ALLOW, yolo.permissionAction());
    assertEquals(ToolInvocationStatus.QUEUED, yolo.initialStatus());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(new ToolCall("bad", "write", "{}")),
                List.of(binding("write")),
                deny,
                true,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW));
    String invalidPathArguments =
        objectMapper.writeValueAsString(Map.of("path", "bad" + (char) 0 + "path"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(new ToolCall("bad-path", "write", invalidPathArguments)),
                List.of(binding("write")),
                deny,
                true,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(new ToolCall("missing", "write", "{\"path\":\"x\"}")),
                List.of(),
                deny,
                true,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW));
  }

  /** descriptor 未指定 timeout 时使用正的 Runtime 默认值，不能生成立即过期 Invocation。 */
  @Test
  void appliesConfiguredDefaultTimeout() {
    Duration defaultTimeout = Duration.ofSeconds(45);
    ToolPreparationService service =
        new ToolPreparationService(
            ids::incrementAndGet,
            new ToolInterceptorChain(List.of(), List.of()),
            new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer()),
            objectMapper,
            defaultTimeout);

    PreparedToolInvocation prepared =
        service
            .prepare(
                List.of(new ToolCall("call", "read", "{\"path\":\"README.md\"}")),
                List.of(binding("read", Duration.ZERO)),
                ToolSettings.DEFAULT,
                false,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW)
            .get(0);

    assertEquals(NOW.plus(defaultTimeout), prepared.deadlineAt());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPreparationService(
                ids::incrementAndGet,
                new ToolInterceptorChain(List.of(), List.of()),
                new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer()),
                objectMapper,
                Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(new ToolCall("overflow", "read", "{\"path\":\"README.md\"}")),
                List.of(binding("read", Duration.ofSeconds(Long.MAX_VALUE))),
                ToolSettings.DEFAULT,
                false,
                Path.of("/tmp/workspace"),
                Path.of("/tmp/workspace"),
                NOW));
  }

  /** 重复 call id、重复 binding name 和空调用在事务写入前明确失败。 */
  @Test
  void rejectsAmbiguousPreparationInput() {
    ToolPreparationService service = service(new ToolInterceptorChain(List.of(), List.of()));
    ToolCall call = new ToolCall("same", "read", "{\"path\":\"x\"}");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(call, call),
                List.of(binding("read")),
                ToolSettings.DEFAULT,
                false,
                Path.of("."),
                Path.of("."),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(call),
                List.of(binding("read"), binding("read")),
                ToolSettings.DEFAULT,
                false,
                Path.of("."),
                Path.of("."),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.prepare(
                List.of(),
                List.of(binding("read")),
                ToolSettings.DEFAULT,
                false,
                Path.of("."),
                Path.of("."),
                NOW));
  }

  private ToolPreparationService service(ToolInterceptorChain chain) {
    return new ToolPreparationService(
        ids::incrementAndGet,
        chain,
        new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer()),
        objectMapper);
  }

  private static ToolBinding binding(String name) {
    return binding(name, Duration.ofSeconds(30));
  }

  private static ToolBinding binding(String name, Duration timeout) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "1",
            name,
            null,
            new ToolParamsSchema(
                "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.IDEMPOTENT,
            timeout);
    return ToolBinding.of(descriptor);
  }
}
