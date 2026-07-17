package fun.fengwk.kkstudio.studio.model;

/** How a Function input or prompt mention selects resources. */
public sealed interface ResourceSelector {

  record ResourceItem(long resourceId) implements ResourceSelector {}

  record ResourceChannel(long ownerNodeId, String channelKey) implements ResourceSelector {}

  record GroupResources(long groupNodeId) implements ResourceSelector {}
}
