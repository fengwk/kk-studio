package fun.fengwk.kkstudio.studio.model;

/** Immutable payload location for a ResourceVersion. */
public sealed interface PayloadRef {

  record InlineText(String text) implements PayloadRef {}

  record InlineJson(String json) implements PayloadRef {}

  record StoredObject(String objectId, String mediaType, long sizeBytes) implements PayloadRef {}

  record ExternalUri(String uri, String mediaType) implements PayloadRef {}
}
