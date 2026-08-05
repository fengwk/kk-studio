/**
 * Production Environment Daemon coding tools.
 *
 * <p>Every filesystem operation enters through one canonical environment root boundary; command
 * permission remains a Platform responsibility while Daemon retains its non-bypassable workdir
 * boundary.
 *
 * <p>Stable capability set: {@code read}, {@code write}, {@code edit}, {@code apply_patch}, {@code
 * bash}, {@code grep}, {@code find}, {@code lsp_goto_definition}, {@code lsp_workspace_symbols},
 * {@code lsp_java_decompile}. LSP tools use an optional local command bridge ({@code
 * kkstudio.daemon.lsp-bridge}); when absent they return an explicit unavailable error, except
 * {@code lsp_java_decompile} which may fall back to {@code javap} for resolvable class targets.
 *
 * <p>Large or binary outputs are stored as immutable resources through {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore} ({@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LocalFileResourceStore} in standalone deployments) and
 * emitted as {@code ResourceToolContent} references.
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
