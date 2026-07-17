import { LIBRARY_CARDS, TEMPLATES } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { getLibraryFilterCounts } from '@/features/canvas/reducer'
import type { LibraryFilter } from '@/features/canvas/types'

export function CanvasLibraryView() {
  const {
    state,
    setIdea,
    createFromIdea,
    selectTemplate,
    setLibraryFilter,
    openEditor,
    setResearchOpen,
    setToast,
  } = useCanvasRuntime()

  const counts = getLibraryFilterCounts(LIBRARY_CARDS)
  const cards = LIBRARY_CARDS.filter((card) => state.libraryFilter === 'all' || card.owner === state.libraryFilter)

  return (
    <section className="view library-view active" id="libraryView" tabIndex={-1} aria-labelledby="libraryTitle">
      <div className="library-content">
        <div className="eyebrow">
          <span className="status-dot" />
          Agent 原生工作区
        </div>
        <div className="library-heading">
          <div>
            <h1 id="libraryTitle">
              把想法、资料和结果
              <br />
              放在同一个空间。
            </h1>
            <p>从一句目标开始，让 Agent 在画布中规划、执行，并留下可以继续编辑的结果。</p>
          </div>
          <button
            className="text-button"
            type="button"
            onClick={(event) => setResearchOpen(true, event.currentTarget)}
          >
            查看调研结论
            {' '}
            <span>↗</span>
          </button>
        </div>

        <div className="idea-composer">
          <span className="sparkle">✦</span>
          <input
            value={state.idea}
            onChange={(event) => setIdea(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                createFromIdea()
              }
            }}
            aria-label="描述想完成的工作"
          />
          <button className="primary-button" type="button" onClick={createFromIdea}>
            从想法创建
            {' '}
            <span>→</span>
          </button>
        </div>

        <section className="template-section" aria-labelledby="templateTitle">
          <div className="section-heading">
            <h2 id="templateTitle">从模板快速开始</h2>
            <button className="text-button" type="button" onClick={() => setToast('全部模板库将在完整产品中打开（原型模拟）')}>
              全部模板
              {' '}
              <span>→</span>
            </button>
          </div>
          <div className="template-grid" role="group" aria-label="画布模板">
            {TEMPLATES.map((template) => {
              const selected = state.selectedTemplate === template.name
              return (
                <button
                  key={template.id}
                  type="button"
                  className={`template-card ${selected ? 'selected' : ''}`}
                  aria-pressed={selected}
                  onClick={() => selectTemplate(template.name)}
                >
                  <span className={`template-icon ${template.iconTone}`}>{template.icon}</span>
                  <strong>{template.name}</strong>
                  <small>{template.description}</small>
                </button>
              )
            })}
          </div>
        </section>

        <section className="canvas-library" aria-labelledby="canvasTitle">
          <div className="section-heading library-toolbar">
            <div>
              <h2 id="canvasTitle">你的画布</h2>
              <div className="filter-tabs" role="group" aria-label="画布筛选">
                {(
                  [
                    ['all', '全部', counts.all],
                    ['mine', '我的', counts.mine],
                    ['collab', '与我协作', counts.collab],
                  ] as const
                ).map(([filter, label, count]) => {
                  const active = state.libraryFilter === filter
                  return (
                    <button
                      key={filter}
                      type="button"
                      className={active ? 'active' : undefined}
                      aria-pressed={active}
                      onClick={() => setLibraryFilter(filter as LibraryFilter)}
                    >
                      {label}
                      {' '}
                      <span>{count}</span>
                    </button>
                  )
                })}
              </div>
            </div>
            <div className="library-controls">
              <button className="search-button" type="button" onClick={() => setToast('画布搜索将在完整产品中打开（原型模拟）')}>
                ⌕ 搜索画布
              </button>
              <button className="icon-button" type="button" aria-label="切换画布网格" onClick={() => setToast('当前使用网格视图（原型模拟）')}>
                ▦
              </button>
            </div>
          </div>

          <div className="project-grid">
            {cards.map((card) => (
              <button
                key={card.id}
                type="button"
                className={`project-card ${card.featured ? 'feature' : ''} ${card.muted ? 'muted' : ''}`}
                data-library-owner={card.owner}
                onClick={openEditor}
              >
                {card.runStateLabel ? (
                  <span className={`project-state ${card.runState ?? ''}`} data-project-run-state>
                    {card.runStateLabel}
                  </span>
                ) : null}
                <div className={`project-preview ${card.preview}-preview`}>
                  <i />
                  <i />
                  <i />
                  {card.preview === 'research' ? <i /> : null}
                  {card.previewBadge ? <b>{card.previewBadge}</b> : null}
                </div>
                <strong>{card.title}</strong>
                <small>
                  <span data-project-object-count>{card.objectsLabel}</span>
                  {' '}
                  ·
                  {' '}
                  {card.editedLabel}
                </small>
                <div className="project-footer">
                  <span>{card.footerLeft}</span>
                  <span>{card.footerRight}</span>
                </div>
              </button>
            ))}
          </div>
        </section>
      </div>
    </section>
  )
}
