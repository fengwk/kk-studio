package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 与 Node runtime-status.ts/protocol.ts 对齐的 wire fixtures 与严格边界。 */
class DaemonRuntimeControlCodecTest {

  private static final String READY_JSON =
      "{\"version\":6,\"daemonVersion\":\"1.0.9\","
          + "\"environment\":{\"operatingSystem\":\"linux\",\"architecture\":\"amd64\","
          + "\"timeZone\":\"Asia/Shanghai\",\"userName\":\"dev\","
          + "\"homeDirectory\":\"/home/dev\",\"note\":\"Linux environment.\","
          + "\"tempDirectory\":\"/tmp/kk-studio\"},"
          + "\"runtimeStatus\":{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
          + "\"update\":null}}}";

  private static final String RUNTIME_JSON =
      "{\"revision\":\"3\",\"status\":{\"daemonVersion\":\"1.0.9\","
          + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":12,"
          + "\"totalBytes\":24}}}";

  private final DaemonReadyCodec readyCodec = new DaemonReadyCodec();
  private final DaemonHeartbeatCodec heartbeatCodec = new DaemonHeartbeatCodec();
  private final DaemonRuntimeStatusCodec runtimeStatusCodec = new DaemonRuntimeStatusCodec();
  private final DaemonDataBindCodec dataBindCodec = new DaemonDataBindCodec();
  private final DaemonIdeLeaseCodec ideLeaseCodec = new DaemonIdeLeaseCodec();

  @Test
  void decodesNodeReadyAndRuntimeFixtures() {
    DaemonReady ready = readyCodec.decode(READY_JSON);
    DaemonRuntimeSnapshot runtime = runtimeStatusCodec.decode(RUNTIME_JSON);

    assertEquals("1.0.9", ready.capabilities().daemonVersion());
    assertEquals(1L, ready.runtimeStatus().revision());
    assertEquals(null, ready.runtimeStatus().status().update());
    assertEquals(3L, runtime.revision());
    assertEquals(new DaemonUpdateProgress("1.0.10", 12L, 24L), runtime.status().update());
    assertEquals(RUNTIME_JSON, runtimeStatusCodec.encode(runtime));
  }

  @Test
  void rejectsSameRevisionDifferentContentAndInvalidUpdateBounds() {
    DaemonRuntimeSnapshot current = runtimeStatusCodec.decode(RUNTIME_JSON);
    DaemonRuntimeSnapshot same =
        runtimeStatusCodec.decode(
            "{\"revision\":\"3\",\"status\":{\"daemonVersion\":\"1.0.9\","
                + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":12,"
                + "\"totalBytes\":24}}}");
    assertEquals(current, same);

    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"4\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":25,"
                    + "\"totalBytes\":24}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"4\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\","
                    + "\"downloadedBytes\":9007199254740992,\"totalBytes\":null}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> runtimeStatusCodec.decode("{\"revision\":\"1\",\"status\":{}}"));
  }

  @Test
  void heartbeatRequiresRuntimeStatusAndLeaseRound() {
    DaemonHeartbeat heartbeat =
        heartbeatCodec.decode(
            "{\"runtimeStatus\":{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                + "\"update\":null}},\"ideLeaseRound\":\"2\"}");
    assertEquals(2L, heartbeat.ideLeaseRound());

    assertThrows(
        DaemonProtocolException.class,
        () ->
            heartbeatCodec.decode(
                "{\"runtimeStatus\":{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            heartbeatCodec.decode(
                "{\"runtimeStatus\":{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}},\"ideLeaseRound\":\"0\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            heartbeatCodec.decode(
                "{\"runtimeStatus\":{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}},\"ideLeaseRound\":null,\"extra\":true}"));
  }

  @Test
  void dataBindIsStrictAndSameOriginIsExplicit() {
    String ticket = "A".repeat(43);
    String json =
        "{\"generation\":\"2\",\"ticket\":\""
            + ticket
            + "\",\"streamUrl\":\"https://studio.example/internal/environment-data\"}";
    DaemonDataBind bind = dataBindCodec.decode(json);
    assertEquals(2L, bind.generation());
    assertTrue(bind.isSameOrigin(URI.create("https://studio.example")));
    assertFalse(bind.isSameOrigin(URI.create("https://other.example")));

    assertThrows(
        DaemonProtocolException.class,
        () ->
            dataBindCodec.decode(
                "{\"generation\":\"2\",\"ticket\":\"short\","
                    + "\"streamUrl\":\"https://studio.example/internal/environment-data\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            dataBindCodec.decode(
                "{\"generation\":\"2\",\"ticket\":\""
                    + ticket
                    + "\",\"streamUrl\":\"https://user@studio.example/x\"}"));
  }

  @Test
  void ideLeaseEnforcesCanonicalBindingsAndLimit() {
    UUID view = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID document = UUID.fromString("22222222-2222-2222-2222-222222222222");
    String json =
        "{\"round\":\"1\",\"bindings\":[{\"viewId\":\""
            + view
            + "\",\"documentId\":\""
            + document
            + "\",\"bindingEpoch\":\"1\"}]}";
    DaemonIdeLease lease = ideLeaseCodec.decode(json);
    assertEquals(1L, lease.round());
    assertEquals(List.of(new DaemonIdeLease.Binding(view, document, 1L)), lease.bindings());

    assertThrows(
        DaemonProtocolException.class,
        () ->
            ideLeaseCodec.decode(
                "{\"round\":\"1\",\"bindings\":["
                    + "{\"viewId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"documentId\":\"22222222-2222-2222-2222-222222222222\","
                    + "\"bindingEpoch\":\"1\"},"
                    + "{\"viewId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"documentId\":\"33333333-3333-3333-3333-333333333333\","
                    + "\"bindingEpoch\":\"2\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            ideLeaseCodec.decode(
                "{\"round\":\"1\",\"bindings\":["
                    + "{\"viewId\":\"11111111-1111-1111-1111-11111111111A\","
                    + "\"documentId\":\"22222222-2222-2222-2222-222222222222\","
                    + "\"bindingEpoch\":\"1\"}]}"));
  }

  @Test
  void ideLeaseRejectsTooManyBindings() {
    List<String> bindings = new ArrayList<>();
    for (int index = 0; index < DaemonIdeLease.MAX_BINDINGS + 1; index++) {
      UUID view = UUID.nameUUIDFromBytes(("view-" + index).getBytes());
      UUID document = UUID.nameUUIDFromBytes(("document-" + index).getBytes());
      bindings.add(
          "{\"viewId\":\""
              + view
              + "\",\"documentId\":\""
              + document
              + "\",\"bindingEpoch\":\"1\"}");
    }
    assertThrows(
        DaemonProtocolException.class,
        () ->
            ideLeaseCodec.decode(
                "{\"round\":\"1\",\"bindings\":[" + String.join(",", bindings) + "]}"));
  }

  @Test
  void rejectsNonCanonicalDecimalStringsAndMissingReadyStatus() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"01\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"0\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":1,\"status\":{\"daemonVersion\":\"1.0.9\",\"update\":null}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            readyCodec.decode(
                READY_JSON.substring(0, READY_JSON.indexOf(",\"runtimeStatus\"")) + "}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> readyCodec.decode(READY_JSON.replace(",\"runtimeStatus\"", ",\"ignored\"")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            readyCodec.decode(
                READY_JSON.replace(
                    "\"daemonVersion\":\"1.0.9\",\"update\":null",
                    "\"daemonVersion\":\"1.0.10\",\"update\":null")));
  }

  @Test
  void readyAndHeartbeatRoundTripStrictPayloads() {
    DaemonReady ready = readyCodec.decode(READY_JSON);
    assertEquals(ready, readyCodec.decode(readyCodec.encode(ready)));

    DaemonHeartbeat heartbeat =
        heartbeatCodec.decode(
            "{\"runtimeStatus\":{\"revision\":\"2\",\"status\":{\"daemonVersion\":\"1.0.9\","
                + "\"update\":null}},\"ideLeaseRound\":null}");
    assertEquals(heartbeat, heartbeatCodec.decode(heartbeatCodec.encode(heartbeat)));
    assertThrows(DaemonProtocolException.class, () -> heartbeatCodec.decode("[]"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            heartbeatCodec.decode(
                "{\"runtimeStatus\":{\"revision\":\"2\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}},\"ideLeaseRound\":null} trailing"));
  }

  @Test
  void runtimeStatusRejectsInvalidProgressBoundsAndShapes() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":-1,"
                    + "\"totalBytes\":null}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":2,"
                    + "\"totalBytes\":1}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":1.0,"
                    + "\"totalBytes\":null}}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":1}}}"));
  }

  @Test
  void dataBindAndIdeLeaseRecordsEnforceStructuralLimits() {
    String ticket = "A".repeat(43);
    assertFalse(
        new DaemonDataBind(1L, ticket, URI.create("https://studio.example/internal/data"))
            .isSameOrigin(URI.create("http://studio.example")));
    assertTrue(
        new DaemonDataBind(1L, ticket, URI.create("https://studio.example/internal/data"))
            .isSameOrigin(URI.create("https://studio.example:443")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDataBind(0L, ticket, URI.create("https://studio.example/internal/data")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDataBind(1L, "A".repeat(44), URI.create("https://studio.example/x")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDataBind(1L, ticket, URI.create("https://studio.example")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDataBind(1L, ticket, URI.create("https://studio.example/x#fragment")));

    UUID view = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID document = UUID.fromString("22222222-2222-2222-2222-222222222222");
    DaemonIdeLease.Binding binding = new DaemonIdeLease.Binding(view, document, 1L);
    assertThrows(IllegalArgumentException.class, () -> new DaemonIdeLease(0L, List.of(binding)));
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonIdeLease(1L, List.of(binding, binding)));
  }

  @Test
  void rejectsUnknownFieldsDuplicateKeysAndTrailingTokens() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null},\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            runtimeStatusCodec.decode(
                "{\"revision\":\"1\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null}} trailing"));
  }
}
