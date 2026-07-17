package fun.fengwk.kkstudio.studio.runtime;

import fun.fengwk.kkstudio.studio.model.PayloadRef;

/** Payload storage port. S3/local adapters live in core. */
public interface ResourceStore {

  PayloadRef.StoredObject put(ResourceWriteRequest request);

  ResourceReadHandle open(String objectId);

  void retain(String objectId);

  void release(String objectId);
}
