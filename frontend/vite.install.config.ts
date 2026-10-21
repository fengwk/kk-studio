import { defineConfig } from 'vitest/config'
import base from './vite.config'

export default defineConfig({
  ...base,
  test: {
    ...base.test,
    include: [
      'src/features/environment/install-command.test.ts',
      'src/features/environment/install-command.windows.test.ts',
      'src/features/environment/EnvironmentInstallModal.test.tsx',
      'src/features/environment/EnvironmentsPage.test.tsx',
      'src/shared/api/environment-service.test.ts',
      'src/shared/ui/feedback/Toast.test.tsx',
      'src/shared/ui/controls/Select.test.tsx',
    ],
    coverage: {
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: '.reports/install-coverage',
      include: [
        'src/features/environment/install-command.ts',
        'src/features/environment/EnvironmentInstallModal.tsx',
        'src/shared/api/environment-service.ts',
        'src/shared/ui/feedback/Toast.tsx',
      ],
      thresholds: { perFile: true, lines: 90, statements: 90, functions: 80, branches: 80 },
    },
  },
})
