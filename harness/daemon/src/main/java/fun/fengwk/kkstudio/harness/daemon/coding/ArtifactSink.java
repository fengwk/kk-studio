package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;

/** Stores complete local tool output outside the bounded model preview. */
public interface ArtifactSink {

  /** Persists bytes and returns their stable reference. */
  ArtifactRef store(byte[] bytes, String mediaType) throws IOException;
}
