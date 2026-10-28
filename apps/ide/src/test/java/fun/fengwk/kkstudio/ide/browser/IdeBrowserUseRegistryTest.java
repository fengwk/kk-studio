package fun.fengwk.kkstudio.ide.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry.Acceptance;

import java.util.List;
import java.util.UUID;

/** 意图：本地登记严格绑定物理连接 generation/document，并按 revision 幂等或原子替换。 */
class IdeBrowserUseRegistryTest {

  private static final UUID EVENT_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CONNECTION = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final ConnectionIdentity IDENTITY = new ConnectionIdentity(EVENT_NODE, CONNECTION);
  private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_DOCUMENT =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");
  private static final UUID VIEW = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID OTHER_VIEW = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID CLAIM = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final UUID OTHER_CLAIM = UUID.fromString("77777777-7777-7777-7777-777777777777");

  private final IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();

  @Test
  void unregisterOnlyDeletesItsOwnGeneration() {
    long first = registry.register(IDENTITY);
    registry.unregister(IDENTITY, first + 1);
    assertTrue(registry.usage(IDENTITY).isEmpty());

    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, first, usage(1, intent())));
    registry.unregister(IDENTITY, first + 1);
    assertTrue(registry.usage(IDENTITY).isPresent(), "stale close must not delete live generation");

    registry.unregister(IDENTITY, first);
    assertTrue(registry.usage(IDENTITY).isEmpty());
    long second = registry.register(IDENTITY);
    assertTrue(second > first);
    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, second, usage(2, intent())));
    registry.unregister(IDENTITY, first);
    assertTrue(
        registry.usage(IDENTITY).isPresent(), "old generation must not delete re-registered");
    registry.unregister(IDENTITY, second);
    assertTrue(registry.usage(IDENTITY).isEmpty());
  }

  @Test
  void rejectsDuplicateActiveConnectionIdentity() {
    registry.register(IDENTITY);
    assertThrows(IllegalStateException.class, () -> registry.register(IDENTITY));
  }

  @Test
  void documentIsFixedPerConnection() {
    long generation = registry.register(IDENTITY);
    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, generation, usage(1, intent())));

    assertEquals(
        Acceptance.DOCUMENT_MISMATCH,
        registry.accept(IDENTITY, generation, new IdeBrowserUse(OTHER_DOCUMENT, 2, List.of())));
    assertEquals(DOCUMENT, registry.usage(IDENTITY).orElseThrow().documentId());
  }

  @Test
  void sameRevisionIsIdempotentOnlyForIdenticalCanonicalContent() {
    long generation = registry.register(IDENTITY);
    IdeBrowserUseView first = intent();
    IdeBrowserUseView second = new IdeBrowserUseView(ENVIRONMENT, OTHER_VIEW, OTHER_CLAIM, null);
    IdeBrowserUse original = usage(2, first, second);
    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, generation, original));

    // 输入顺序不同但集合内容相同：规范化后仍是同一 revision 的相同内容。
    assertEquals(
        Acceptance.UNCHANGED, registry.accept(IDENTITY, generation, usage(2, second, first)));
    assertEquals(
        Acceptance.REVISION_CONFLICT,
        registry.accept(
            IDENTITY,
            generation,
            usage(2, new IdeBrowserUseView(ENVIRONMENT, VIEW, OTHER_CLAIM, null))));
    assertEquals(
        Acceptance.REVISION_CONFLICT, registry.accept(IDENTITY, generation, usage(1, first)));
  }

  @Test
  void higherRevisionAtomicallyReplacesTheWholeList() {
    long generation = registry.register(IDENTITY);
    registry.accept(IDENTITY, generation, usage(1, intent()));
    IdeBrowserUseView bound = new IdeBrowserUseView(ENVIRONMENT, VIEW, CLAIM, 9L);

    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, generation, usage(2, bound)));
    IdeBrowserUse current = registry.usage(IDENTITY).orElseThrow();
    assertEquals(2, current.revision());
    assertEquals(9L, current.views().getFirst().bindingEpoch());

    // 低 revision 即使内容相同也不能回退状态。
    assertEquals(
        Acceptance.REVISION_CONFLICT, registry.accept(IDENTITY, generation, usage(1, intent())));
    assertEquals(2, registry.usage(IDENTITY).orElseThrow().revision());
  }

  @Test
  void exactIntentRequiresLiveConnectionDocumentAndAllClaimFields() {
    long generation = registry.register(IDENTITY);
    registry.accept(IDENTITY, generation, usage(1, intent()));

    assertTrue(registry.findExactIntent(IDENTITY, DOCUMENT, ENVIRONMENT, VIEW, CLAIM).isPresent());
    assertTrue(
        registry.findExactIntent(IDENTITY, OTHER_DOCUMENT, ENVIRONMENT, VIEW, CLAIM).isEmpty());
    assertTrue(
        registry.findExactIntent(IDENTITY, DOCUMENT, ENVIRONMENT, VIEW, OTHER_CLAIM).isEmpty());
    assertTrue(
        registry.findExactIntent(IDENTITY, DOCUMENT, ENVIRONMENT, OTHER_VIEW, CLAIM).isEmpty());

    registry.unregister(IDENTITY, generation);
    assertTrue(registry.findExactIntent(IDENTITY, DOCUMENT, ENVIRONMENT, VIEW, CLAIM).isEmpty());
  }

  @Test
  void activeBoundProofsExcludeIntentsWithoutEpoch() {
    long generation = registry.register(IDENTITY);
    registry.accept(IDENTITY, generation, usage(1, intent()));
    assertTrue(registry.activeBoundProofs(IDENTITY, DOCUMENT).isEmpty());

    IdeBrowserUseView bound = new IdeBrowserUseView(ENVIRONMENT, OTHER_VIEW, CLAIM, 3L);
    registry.accept(IDENTITY, generation, usage(2, bound, intent()));
    IdeBrowserUse proofs = registry.activeBoundProofs(IDENTITY, DOCUMENT).orElseThrow();
    assertEquals(1, proofs.views().size());
    assertEquals(OTHER_VIEW, proofs.views().getFirst().viewId());

    IdeBrowserUse empty = new IdeBrowserUse(DOCUMENT, 3, List.of());
    assertEquals(Acceptance.ACCEPTED, registry.accept(IDENTITY, generation, empty));
    assertTrue(registry.activeBoundProofs(IDENTITY, DOCUMENT).isEmpty());
  }

  @Test
  void closedGenerationCannotAcceptFurtherUsage() {
    long generation = registry.register(IDENTITY);
    registry.unregister(IDENTITY, generation);
    assertEquals(
        Acceptance.DISCONNECTED, registry.accept(IDENTITY, generation, usage(1, intent())));
    assertFalse(registry.usage(IDENTITY).isPresent());
  }

  private static IdeBrowserUse usage(long revision, IdeBrowserUseView... views) {
    return new IdeBrowserUse(DOCUMENT, revision, List.of(views));
  }

  private static IdeBrowserUseView intent() {
    return new IdeBrowserUseView(ENVIRONMENT, VIEW, CLAIM, null);
  }
}
