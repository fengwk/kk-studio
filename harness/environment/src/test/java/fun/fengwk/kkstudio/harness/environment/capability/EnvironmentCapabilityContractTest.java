package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Environment Capability descriptor、参数、请求、结果和流式 SPI 契约测试。 */
class EnvironmentCapabilityContractTest {

  private static final InputSchema SCHEMA =
      new InputSchema(
          null,
          Map.of("path", new StringSchema(null), "offset", new IntegerSchema(null)),
          Set.of("path"),
          false);

  private static final EnvironmentCapabilityDescriptor DESCRIPTOR =
      new EnvironmentCapabilityDescriptor(
          new EnvironmentCapabilityId("fs.read"), "1.0.0", SCHEMA, Duration.ofSeconds(10));

  /** 反射锁定 capability descriptor 只有执行所需字段，不引入模型展示、renderer 或权限元数据。 */
  @Test
  void descriptorContainsOnlyCapabilityFields() {
    RecordComponent[] components = EnvironmentCapabilityDescriptor.class.getRecordComponents();

    assertArrayEquals(
        new String[] {"id", "version", "inputSchema", "defaultTimeout"},
        componentNames(components));
    assertEquals(EnvironmentCapabilityId.class, components[0].getType());
    assertEquals(String.class, components[1].getType());
    assertEquals(InputSchema.class, components[2].getType());
    assertEquals(Duration.class, components[3].getType());
  }

  /** 反射锁定执行请求只包含 descriptor、call 与已解析 timeout：workdir 只存在于具体 arguments。 */
  @Test
  void requestContainsOnlyCapabilityExecutionFields() {
    assertArrayEquals(
        new String[] {"descriptor", "call", "timeout"},
        componentNames(EnvironmentCapabilityExecutionRequest.class.getRecordComponents()));
  }

  /** descriptor 版本必须非空，defaultTimeout 允许 zero 但不能为负。 */
  @Test
  void validatesDescriptorBounds() {
    assertEquals(
        Duration.ZERO,
        new EnvironmentCapabilityDescriptor(
                new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ZERO)
            .defaultTimeout());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityDescriptor(
                new EnvironmentCapabilityId("fs.read"), " ", SCHEMA, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityDescriptor(
                new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ofSeconds(-1)));
  }

  /** 数字字符串经过归一化器改为 JSON integer，且原调用保持不变。 */
  @Test
  void normalizesCapabilityCallArguments() {
    EnvironmentCapabilityCall original =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":\"10\",\"path\":\"README.md\"}");

    EnvironmentCapabilityCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":\"10\",\"path\":\"README.md\"}", original.argumentsJson());
    assertEquals("{\"offset\":10,\"path\":\"README.md\"}", normalized.argumentsJson());
    assertEquals("call-1", normalized.id());
    assertNotSame(original, normalized);
  }

  /** strict Provider 对原可选 offset 回传 null 时，Environment 执行边界同样把它等价为缺省。 */
  @Test
  void removesOptionalNullFromCapabilityCall() {
    EnvironmentCapabilityCall original =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":null,\"path\":\"README.md\"}");

    EnvironmentCapabilityCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"path\":\"README.md\"}", normalized.argumentsJson());
    assertEquals("{\"offset\":null,\"path\":\"README.md\"}", original.argumentsJson());
  }

  /** 数字字符串经过现有归一化器改为 JSON integer，归一化后仍需通过 schema 校验。 */
  @Test
  void normalizesAndValidatesNumericArguments() {
    EnvironmentCapabilityCall normalized =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":\"10\",\"path\":\"README.md\"}")
            .validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":10,\"path\":\"README.md\"}", normalized.argumentsJson());
  }

  /** 调用 id 必须非空，参数必须是 JSON object，且 schema 错误不能被归一化吞掉。 */
  @Test
  void rejectsInvalidCapabilityCalls() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityCall(" ", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentCapabilityCall("call-1", "[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityCall("call-1", "{\"offset\":\"bad\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityCall("call-1", "{\"extra\":true,\"path\":\"a\"}")
                .validateFor(DESCRIPTOR));
  }

  /** 请求构造时完成参数校验，并原样保留调用方给出的 timeout（0 表示无 deadline），不做任何回落。 */
  @Test
  void requestNormalizesCallAndKeepsGivenTimeout() {
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            DESCRIPTOR,
            new EnvironmentCapabilityCall("call-1", "{\"offset\":\"20\",\"path\":\"README.md\"}"),
            Duration.ZERO);

    assertEquals("{\"offset\":20,\"path\":\"README.md\"}", request.call().argumentsJson());
    assertEquals(Duration.ZERO, request.timeout());
  }

  /** timeout 不能为负；请求外壳不携带 workdir（目录只存在于 arguments）。 */
  @Test
  void validatesRequestTimeoutAndCarriesNoWorkdir() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityExecutionRequest(
                DESCRIPTOR,
                new EnvironmentCapabilityCall("call-1", "{\"path\":\"a\"}"),
                Duration.ofSeconds(-1)));
    assertEquals(
        List.of("descriptor", "call", "timeout"),
        Arrays.stream(EnvironmentCapabilityExecutionRequest.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  /** Capability result 的 details JSON 默认值、object 校验和 content 数量边界。 */
  @Test
  void enforcesCapabilityResultBoundaries() {
    assertEquals(64, EnvironmentCapabilityResult.MAX_CONTENT_ITEMS);
    assertEquals(1024 * 1024, EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES);
    assertEquals(
        "{}", new EnvironmentCapabilityResult("call-1", List.of(), false, " ").detailsJson());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", List.of(), false, "[]"));

    List<ResultContent> atLimit = new ArrayList<>();
    for (int index = 0; index < EnvironmentCapabilityResult.MAX_CONTENT_ITEMS; index++) {
      atLimit.add(new TextResultContent("x"));
    }
    assertEquals(
        EnvironmentCapabilityResult.MAX_CONTENT_ITEMS,
        new EnvironmentCapabilityResult("call-1", atLimit, false, "{}").contents().size());

    List<ResultContent> overLimit = new ArrayList<>(atLimit);
    overLimit.add(new TextResultContent("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", overLimit, false, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult(" ", List.of(), false, "{}"));
    assertThrows(
        NullPointerException.class,
        () -> new EnvironmentCapabilityResult("call-1", null, false, "{}"));
  }

  /** details JSON 的原始 UTF-8 上限校验。 */
  @Test
  void enforcesCapabilityResultDetailsByteLimit() {
    String atLimit =
        "{\"d\":\""
            + "a".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES - 8)
            + "\"}";
    assertEquals(
        atLimit,
        new EnvironmentCapabilityResult("call-1", List.of(), false, atLimit).detailsJson());

    String overLimit =
        "{\"d\":\""
            + "a".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES - 7)
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", List.of(), false, overLimit));
  }

  /** fake capability 按顺序发送两个 partial 后 complete，并以 handle 提供幂等 cancel 形状。 */
  @Test
  void streamsOrderedPartialsAndOneTerminalWithCancellableHandle() {
    FakeCapability capability = new FakeCapability(DESCRIPTOR);
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            DESCRIPTOR,
            new EnvironmentCapabilityCall("call-1", "{\"path\":\"README.md\"}"),
            Duration.ZERO);

    EnvironmentCapabilityExecutionHandle handle = capability.execute(request, listener);

    assertEquals(List.of("partial-1", "partial-2", "complete"), listener.events);
    assertNull(listener.error);
    assertFalse(handle.isCancelled());
    handle.cancel();
    handle.cancel();
    assertTrue(handle.isCancelled());
  }

  private static String[] componentNames(RecordComponent[] components) {
    String[] names = new String[components.length];
    for (int index = 0; index < components.length; index++) {
      names[index] = components[index].getName();
    }
    return names;
  }

  private static final class FakeCapability implements EnvironmentCapability {
    private final EnvironmentCapabilityDescriptor descriptor;

    private FakeCapability(EnvironmentCapabilityDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public EnvironmentCapabilityDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle execute(
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      assertEquals(descriptor, request.descriptor());
      AtomicBoolean terminal = new AtomicBoolean();
      EnvironmentCapabilityExecutionListener fenced =
          new EnvironmentCapabilityExecutionListener() {
            @Override
            public void onPartial(EnvironmentCapabilityResult partial) {
              if (!terminal.get()) {
                listener.onPartial(partial);
              }
            }

            @Override
            public void onComplete(EnvironmentCapabilityResult result) {
              if (terminal.compareAndSet(false, true)) {
                listener.onComplete(result);
              }
            }

            @Override
            public void onError(Throwable error) {
              if (terminal.compareAndSet(false, true)) {
                listener.onError(error);
              }
            }
          };
      fenced.onPartial(result(request, "partial-1"));
      fenced.onPartial(result(request, "partial-2"));
      fenced.onComplete(result(request, "complete"));
      fenced.onPartial(result(request, "ignored"));
      fenced.onError(new IllegalStateException("ignored"));

      AtomicBoolean cancelled = new AtomicBoolean();
      return new EnvironmentCapabilityExecutionHandle() {
        @Override
        public void cancel() {
          cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
          return cancelled.get();
        }
      };
    }

    private EnvironmentCapabilityResult result(
        EnvironmentCapabilityExecutionRequest request, String text) {
      return new EnvironmentCapabilityResult(
          request.call().id(), List.of(new TextResultContent(text)), false, "{}");
    }
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final List<String> events = new ArrayList<>();
    private Throwable error;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      events.add(((TextResultContent) partial.contents().getFirst()).text());
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      events.add(((TextResultContent) result.contents().getFirst()).text());
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      events.add("error");
    }
  }
}
