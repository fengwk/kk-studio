package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.model.PayloadRef;
import fun.fengwk.kkstudio.studio.runtime.ResourceReadHandle;
import fun.fengwk.kkstudio.studio.runtime.ResourceStore;
import fun.fengwk.kkstudio.studio.runtime.ResourceWriteRequest;

/** TODO: replace with S3-backed ResourceStore using core.storage. */
public class StubResourceStore implements ResourceStore {

  @Override
  public PayloadRef.StoredObject put(ResourceWriteRequest request) {
    throw new StudioFeatureNotReadyException("ResourceStore.put");
  }

  @Override
  public ResourceReadHandle open(String objectId) {
    throw new StudioFeatureNotReadyException("ResourceStore.open");
  }

  @Override
  public void retain(String objectId) {
    throw new StudioFeatureNotReadyException("ResourceStore.retain");
  }

  @Override
  public void release(String objectId) {
    throw new StudioFeatureNotReadyException("ResourceStore.release");
  }
}
