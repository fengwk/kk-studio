package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Canvas Function SPI 的值类型、默认端口行为与公开异常契约。 */
class CanvasFunctionSpiContractTest {

  private static final UUID CANVAS_ID = new UUID(0L, 10);
  private static final UUID NODE_ID = new UUID(0L, 20);
  private static final UUID REQUEST_ID = new UUID(0L, 30);
  private static final UUID TARGET_ID = new UUID(0L, 40);

  /** 引用策略冻结输入副本，并按 kind 回退到 maxReferences。 */
  @Test
  void referencePolicyFreezesBoundsAndFallsBackToMaxReferences() {
    Set<CanvasResourceKind> kinds = new HashSet<>(Set.of(CanvasResourceKind.IMAGE));
    Map<CanvasResourceKind, Integer> perKind = new HashMap<>();
    perKind.put(CanvasResourceKind.IMAGE, 1);
    CanvasFunctionReferencePolicy policy = new CanvasFunctionReferencePolicy(kinds, 3, perKind);
    kinds.add(CanvasResourceKind.VIDEO);
    perKind.put(CanvasResourceKind.VIDEO, 2);

    assertEquals(Set.of(CanvasResourceKind.IMAGE), policy.allowedKinds());
    assertEquals(Map.of(CanvasResourceKind.IMAGE, 1), policy.maxByKind());
    assertEquals(1, policy.maxFor(CanvasResourceKind.IMAGE));
    assertEquals(3, policy.maxFor(CanvasResourceKind.VIDEO));
  }

  /** 引用策略构造期拒绝空/TEXT allowedKinds、负上限与越界的 maxByKind。 */
  @Test
  void referencePolicyRejectsInvalidBounds() {
    assertThrows(
        NullPointerException.class, () -> new CanvasFunctionReferencePolicy(null, 1, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionReferencePolicy(Set.of(), 1, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.TEXT), 1, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), -1, Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionReferencePolicy(
                Set.of(CanvasResourceKind.IMAGE), 2, Map.of(CanvasResourceKind.VIDEO, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionReferencePolicy(
                Set.of(CanvasResourceKind.IMAGE), 1, Map.of(CanvasResourceKind.IMAGE, 2)));
  }

  /** adapter 未覆盖 cancel 时 default cancel 是空操作，不会顺带 submit/execute。 */
  @Test
  void defaultCancelNeverSubmitsOrExecutes() {
    CanvasFunctionFrozenRun run = frozenRun(CanvasFunctionSubmitState.PENDING);
    NoopCancelAdapter adapter = new NoopCancelAdapter();

    assertDoesNotThrow(() -> adapter.cancel(run));
  }

  /** 资源流暴露内容与大小，close 转发到底层 closeable，构造期拒绝负大小与 null。 */
  @Test
  void resourceStreamExposesContentAndClosesDelegate() throws Exception {
    InputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    List<String> closed = new ArrayList<>();
    CanvasFunctionResourceStream stream =
        new CanvasFunctionResourceStream(content, 3L, () -> closed.add("closed"));

    assertSame(content, stream.content());
    assertEquals(3L, stream.size());
    stream.close();
    assertEquals(List.of("closed"), closed);

    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionResourceStream(content, -1L, () -> {}));
    assertThrows(
        NullPointerException.class, () -> new CanvasFunctionResourceStream(null, 0L, () -> {}));
    assertThrows(
        NullPointerException.class, () -> new CanvasFunctionResourceStream(content, 0L, null));
  }

  /** close 原样抛出 IOException，非 IO 异常包装为 IOException 并保留原因。 */
  @Test
  void resourceStreamCloseNormalizesFailures() {
    IOException io = new IOException("io");
    CanvasFunctionResourceStream ioStream =
        new CanvasFunctionResourceStream(
            new ByteArrayInputStream(new byte[0]),
            0L,
            () -> {
              throw io;
            });
    assertSame(io, assertThrows(IOException.class, ioStream::close));

    RuntimeException runtime = new IllegalStateException("runtime");
    CanvasFunctionResourceStream runtimeStream =
        new CanvasFunctionResourceStream(
            new ByteArrayInputStream(new byte[0]),
            0L,
            () -> {
              throw runtime;
            });
    assertSame(runtime, assertThrows(IOException.class, runtimeStream::close).getCause());
  }

  /** unknown 异常拒绝空白消息。 */
  @Test
  void unknownExceptionRejectsBlankMessage() {
    assertEquals("uncertain", new CanvasFunctionUnknownException("uncertain").getMessage());
    assertThrows(IllegalArgumentException.class, () -> new CanvasFunctionUnknownException(" "));
    assertThrows(IllegalArgumentException.class, () -> new CanvasFunctionUnknownException(null));
  }

  private static CanvasFunctionFrozenRun frozenRun(CanvasFunctionSubmitState submitState) {
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            "contract-fn",
            "contract-fn",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],"
                    + "\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    return new CanvasFunctionFrozenRun(
        CANVAS_ID,
        NODE_ID,
        "node",
        REQUEST_ID,
        new CanvasFunctionBinding(definition),
        CanvasJson.parseObject("{}"),
        List.of(),
        List.of(new CanvasFunctionFrozenOutput(TARGET_ID, 0, CanvasResourceKind.IMAGE, "out.png")),
        submitState,
        "stage-0",
        Map.of("k", "v"));
  }

  /** 只管 cancel 契约：其余入口抛出，确保 default cancel 不触发任何执行。 */
  private static final class NoopCancelAdapter implements CanvasFunctionAdapter {

    @Override
    public List<CanvasFunctionDefinition> functions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean enabled() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String unavailableReason() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void preflight(CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }
  }
}
