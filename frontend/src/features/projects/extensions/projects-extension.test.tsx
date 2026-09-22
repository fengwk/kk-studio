import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { ProjectDetailRoute } from './projects-extension'

vi.mock('@/features/projects/ProjectDetailPage', () => ({
  ProjectDetailPage: ({ projectId }: { projectId: string }) => {
    const [draft, setDraft] = useState('')
    return (
      <div>
        <span>{projectId}</span>
        <input
          aria-label="project-local-draft"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
      </div>
    )
  },
}))

function ProjectRouteHarness() {
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => navigate('/projects/project-b')}>
        Switch project
      </button>
      <Routes>
        <Route path="/projects/:projectId" element={<ProjectDetailRoute />} />
      </Routes>
    </>
  )
}

describe('projects extension routes', () => {
  it('remounts project detail state when the route project changes', async () => {
    // 测试意图：同一路由切换 Project 时必须丢弃旧 Project 的弹窗、草稿与选中 Issue 状态。
    render(
      <MemoryRouter initialEntries={['/projects/project-a']}>
        <ProjectRouteHarness />
      </MemoryRouter>,
    )

    expect(await screen.findByText('project-a')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('project-local-draft'), {
      target: { value: 'project-a draft' },
    })
    expect(screen.getByLabelText('project-local-draft')).toHaveValue('project-a draft')

    fireEvent.click(screen.getByRole('button', { name: 'Switch project' }))

    expect(await screen.findByText('project-b')).toBeInTheDocument()
    expect(screen.getByLabelText('project-local-draft')).toHaveValue('')
  })
})
