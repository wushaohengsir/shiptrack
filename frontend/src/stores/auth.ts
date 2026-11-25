import { defineStore } from 'pinia'

const TOKEN_KEY = 'shiptrack_token'

/**
 * Only the access token lives in Pinia (§5.3 of the tech-stack doc):
 * sessionStorage, never localStorage, never in the URL.
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: sessionStorage.getItem(TOKEN_KEY) ?? ''
  }),
  getters: {
    isAuthenticated: (state) => state.token.trim().length > 0
  },
  actions: {
    setToken(token: string) {
      const trimmed = token.trim()
      this.token = trimmed
      if (trimmed) {
        sessionStorage.setItem(TOKEN_KEY, trimmed)
      } else {
        sessionStorage.removeItem(TOKEN_KEY)
      }
    },
    clear() {
      this.token = ''
      sessionStorage.removeItem(TOKEN_KEY)
    }
  }
})
