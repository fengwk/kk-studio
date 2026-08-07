import { useLayoutEffect, useRef, useState } from 'react'
import { GENERATION_PROFILES } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { computeGenerationPanelPosition } from '@/features/canvas/geometry'
import { SendIcon } from '@/features/canvas/icons'
import { useI18n } from '@/shared/i18n'

export function CanvasGenerationWorkbench() {
  const {
    state,
    activeGenerator,
    stageMetrics,
    generationPromptRef,
    setGenerationPrompt,
    commitGenerationPrompt,
    toggleGenerationExpanded,
    closeGenerator,
    setGenerationCapability,
    cycleParameter,
    toggleReference,
    submitGeneration,
    setToast,
  } = useCanvasRuntime()
  const { t } = useI18n()

  const panelRef = useRef<HTMLElement>(null)
  const [panelHeight, setPanelHeight] = useState(330)

  useLayoutEffect(() => {
    if (!activeGenerator || !panelRef.current) {
      return
    }
    const panel = panelRef.current
    // 测量未受约束的内容高度，避免移动端 maxHeight 永久钳制桌面端。
    const previousMaxHeight = panel.style.maxHeight
    panel.style.maxHeight = 'none'
    const measured = panel.scrollHeight || 330
    panel.style.maxHeight = previousMaxHeight
    setPanelHeight(measured)
  }, [
    activeGenerator,
    state.generationPromptDraft,
    state.generationPanelExpanded,
    stageMetrics.width,
    stageMetrics.height,
    activeGenerator?.capability,
    activeGenerator?.parameterIndexes,
    activeGenerator?.references,
    activeGenerator?.status,
  ])

  if (!activeGenerator) {
    return (
      <section
        className="generation-panel"
        hidden
        inert
        aria-hidden
        aria-labelledby="generationPanelTitle"
      />
    )
  }

  const profile = GENERATION_PROFILES[activeGenerator.generationMode]
  const position = computeGenerationPanelPosition({
    node: activeGenerator,
    viewport: state.viewport,
    stage: stageMetrics,
    expanded: state.generationPanelExpanded,
    naturalPanelHeight: panelHeight,
  })
  const statusLabel = t(
    activeGenerator.status === 'generated'
      ? 'canvas.generation.status.generated'
      : 'canvas.generation.status.draft',
  )
  const placeholder = t(`canvas.generation.placeholder.${activeGenerator.generationMode}`)
  const profileLabel = t(profile.labelKey)
  const referenceLabels = [
    'canvas.generation.reference.one',
    'canvas.generation.reference.two',
    'canvas.generation.reference.three',
  ] as const

  return (
    <section
      ref={panelRef}
      className={`generation-panel ${state.generationPanelExpanded ? 'expanded' : ''}`}
      id="generationPanel"
      aria-labelledby="generationPanelTitle"
      data-placement={position.placement}
      style={{
        left: position.left,
        top: position.top,
        maxHeight: position.maxHeight,
        width: position.panelWidth,
      }}
    >
      <div className="generation-panel-head">
        <div>
          <span className="generation-node-kicker">{t('canvas.generation.nodeKicker')}</span>
          <strong id="generationPanelTitle">{profileLabel}</strong>
          <span className={`generation-node-status ${activeGenerator.status}`}>{statusLabel}</span>
        </div>
        <div className="generation-panel-actions">
          <button
            id="toggleGenerationPanel"
            type="button"
            aria-label={t(state.generationPanelExpanded ? 'canvas.generation.collapse' : 'canvas.generation.expand')}
            aria-expanded={state.generationPanelExpanded}
            onClick={toggleGenerationExpanded}
          >
            ↔
          </button>
          <button className="close-panel" type="button" aria-label={t('canvas.generation.close')} onClick={() => closeGenerator(true)}>
            ×
          </button>
        </div>
      </div>

      <div className="generation-capabilities" role="group" aria-label={t('canvas.generation.capabilities')}>
        {profile.capabilities.map((capability) => (
          <button
            key={capability}
            type="button"
            aria-pressed={activeGenerator.capability === capability}
            onClick={() => setGenerationCapability(capability)}
          >
            {t(capability)}
          </button>
        ))}
      </div>

      <div className="reference-row" role="group" aria-label={t('canvas.generation.references')}>
        {activeGenerator.references.map((selected, index) => (
          <button
            key={`ref-${index}`}
            type="button"
            className={`reference-thumb tone-${index === 0 ? 'one' : index === 1 ? 'two' : 'three'} ${selected ? 'selected' : ''}`}
            aria-label={t(referenceLabels[index] ?? referenceLabels[0])}
            aria-pressed={selected}
            onClick={() => toggleReference(index)}
          />
        ))}
        <button
          className="reference-add"
          type="button"
          aria-label={t('canvas.generation.addReference')}
          onClick={() => setToast(t('canvas.toast.generation.addReference'))}
        >
          ＋
        </button>
      </div>

      <label className="generation-prompt-label" htmlFor="generationPrompt">{t('canvas.generation.promptLabel')}</label>
      <textarea
        id="generationPrompt"
        ref={generationPromptRef}
        rows={3}
        placeholder={placeholder}
        value={state.generationPromptDraft}
        onChange={(event) => setGenerationPrompt(event.target.value)}
        onBlur={commitGenerationPrompt}
      />

      <div className="generation-footer">
        <div className="parameter-chips" id="parameterChips">
          {profile.groups.map((group, index) => {
            const value = group.values[activeGenerator.parameterIndexes[index] ?? 0] ?? group.values[0]
            return (
              <button key={group.key} type="button" onClick={() => cycleParameter(index)}>
                {t(group.key)}
                {' '}
                ·
                {' '}
                {t(value)}
              </button>
            )
          })}
        </div>
        <div className="generation-submit">
          <span id="generationCost">{t('canvas.generation.cost', { points: profile.cost })}</span>
          <button id="submitGeneration" type="button" aria-label={t('canvas.generation.submit', { label: profileLabel })} onClick={submitGeneration}>
            <SendIcon />
          </button>
        </div>
      </div>
    </section>
  )
}
