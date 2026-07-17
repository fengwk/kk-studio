import { useLayoutEffect, useRef, useState } from 'react'
import { GENERATION_PROFILES } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { computeGenerationPanelPosition } from '@/features/canvas/geometry'
import { SendIcon } from '@/features/canvas/icons'

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

  const panelRef = useRef<HTMLElement>(null)
  const [panelHeight, setPanelHeight] = useState(330)

  useLayoutEffect(() => {
    if (!activeGenerator || !panelRef.current) {
      return
    }
    const panel = panelRef.current
    // Measure unconstrained content height so mobile maxHeight does not permanently clamp desktop.
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
  const statusLabel = activeGenerator.status === 'generated' ? '已生成' : '草稿'
  const placeholder = `描述要生成的${activeGenerator.generationMode === 'text' ? '文本' : activeGenerator.generationMode === 'image' ? '画面' : '镜头'}…`

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
          <span className="generation-node-kicker">生成节点</span>
          <strong id="generationPanelTitle">{profile.label}</strong>
          <span className={`generation-node-status ${activeGenerator.status}`}>{statusLabel}</span>
        </div>
        <div className="generation-panel-actions">
          <button
            id="toggleGenerationPanel"
            type="button"
            aria-label={state.generationPanelExpanded ? '收起生成操作台宽度' : '展开生成操作台宽度'}
            aria-expanded={state.generationPanelExpanded}
            onClick={toggleGenerationExpanded}
          >
            ↔
          </button>
          <button className="close-panel" type="button" aria-label="关闭生成操作台" onClick={() => closeGenerator(true)}>
            ×
          </button>
        </div>
      </div>

      <div className="generation-capabilities" role="group" aria-label="生成能力">
        {profile.capabilities.map((capability) => (
          <button
            key={capability}
            type="button"
            aria-pressed={activeGenerator.capability === capability}
            onClick={() => setGenerationCapability(capability)}
          >
            {capability}
          </button>
        ))}
      </div>

      <div className="reference-row" role="group" aria-label="参考素材">
        {activeGenerator.references.map((selected, index) => (
          <button
            key={`ref-${index}`}
            type="button"
            className={`reference-thumb tone-${index === 0 ? 'one' : index === 1 ? 'two' : 'three'} ${selected ? 'selected' : ''}`}
            aria-label={`参考素材${['一', '二', '三'][index]}`}
            aria-pressed={selected}
            onClick={() => toggleReference(index)}
          />
        ))}
        <button className="reference-add" type="button" aria-label="添加参考素材" onClick={() => setToast('参考素材选择器将在完整产品中打开（原型模拟）')}>
          ＋
        </button>
      </div>

      <label className="generation-prompt-label" htmlFor="generationPrompt">Prompt</label>
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
                {group.key}
                {' '}
                ·
                {' '}
                {value}
              </button>
            )
          })}
        </div>
        <div className="generation-submit">
          <span id="generationCost">{profile.cost}</span>
          <button id="submitGeneration" type="button" aria-label={`提交${profile.label}`} onClick={submitGeneration}>
            <SendIcon />
          </button>
        </div>
      </div>
    </section>
  )
}
