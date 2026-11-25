/// <reference types="vite/client" />
/// <reference types="element-plus/global" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** `mock` (default) or `http`; the mock layer needs no backend. */
  readonly VITE_API_MODE?: 'mock' | 'http'
  /** Prefix for the real API, e.g. `/api/v1` (default); the dev proxy forwards `/api` to the backend. Only used when VITE_API_MODE=http. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
