import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import type { ApiErrorBody } from './types'
import { MockHttpError, mockRequest } from './mock'

export type ApiMode = 'mock' | 'http'

const configuredMode = (import.meta.env.VITE_API_MODE ?? 'mock').toLowerCase()
export const apiMode: ApiMode = configuredMode === 'http' ? 'http' : 'mock'
export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1').replace(/\/+$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

type QueryValue = string | number | boolean | null | undefined
export type Query = Record<string, QueryValue>

function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof MockHttpError) {
    return new ApiError(error.status, error.code, error.message)
  }
  if (error instanceof Error) {
    return new ApiError(0, 'NETWORK_ERROR', error.message)
  }
  return new ApiError(0, 'UNKNOWN_ERROR', '未知错误')
}

function buildQuery(query: Query): string {
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  })
  const text = params.toString()
  return text ? `?${text}` : ''
}

/**
 * The single entry point for every API call (§5.1 of the tech-stack doc).
 * Mock mode returns the same PagedResponse/DTOs without a backend; HTTP mode
 * adds the bearer token and owns 401 handling.
 */
export async function apiGet<T>(path: string, query: Query = {}): Promise<T> {
  try {
    if (apiMode === 'mock') {
      return await mockRequest<T>('GET', path, query)
    }

    const auth = useAuthStore()
    const response = await fetch(`${apiBaseUrl}${path}${buildQuery(query)}`, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {})
      },
      credentials: 'same-origin'
    })

    if (response.status === 401) {
      auth.clear()
      ElMessage.error('访问令牌已失效，请重新输入')
      window.location.assign('/login')
      throw new ApiError(401, 'UNAUTHORIZED', '访问令牌已失效')
    }

    if (!response.ok) {
      let body: ApiErrorBody | null = null
      try {
        body = (await response.json()) as ApiErrorBody
      } catch {
        // A non-JSON error page still gets a readable message below.
      }
      throw new ApiError(
        response.status,
        body?.error?.code ?? 'HTTP_ERROR',
        body?.error?.message ?? `请求失败（HTTP ${response.status}）`
      )
    }

    return (await response.json()) as T
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.code !== 'UNAUTHORIZED') {
      ElMessage.error(apiError.message)
    }
    throw apiError
  }
}
