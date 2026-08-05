package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.io.IOException;

/** Stores complete Environment Tool output as immutable resources outside the bounded preview. */
public interface ResourceSink {

  /** Persists bytes and returns their stable canonical reference. */
  ResourceRef store(byte[] bytes, String mediaType) throws IOException;
}
