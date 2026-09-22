import axios from 'axios'

const api = axios.create({
  baseURL: '',
  headers: {
    'Content-Type': 'application/json'
  },
  timeout: 10000
})

export const TOKEN_KEY = 'collab-token'
export const TOKEN_EXPIRY_KEY = 'collab-token-expires-at'

/**
 * Returns the stored token only while it is still usable. A browser cannot see the HTTP status of a
 * rejected WebSocket handshake, so an expired token has to be detected here or the socket would retry
 * against a credential that can never succeed.
 */
export const readToken = () => {
  const expiresAt = Number(localStorage.getItem(TOKEN_EXPIRY_KEY))
  if (expiresAt && Date.now() >= expiresAt) return null
  return localStorage.getItem(TOKEN_KEY)
}

export const storeToken = (token, expiresInSeconds) => {
  localStorage.setItem(TOKEN_KEY, token)
  if (expiresInSeconds) {
    localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + Number(expiresInSeconds) * 1000))
  }
}

/**
 * Registered by `session.js`. Clearing localStorage alone would leave the reactive user in place, so the
 * router guard would still think we are signed in and every later call would 401 again with no way back
 * to the login form without a manual reload.
 */
let onUnauthorized = null
export const setUnauthorizedHandler = (handler) => { onUnauthorized = handler }

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      switch (status) {
        case 401:
          // The token is missing, expired, or its account was disabled or deleted mid-session.
          localStorage.removeItem(TOKEN_KEY)
          localStorage.removeItem('user')
          if (onUnauthorized) onUnauthorized()
          break
        case 403:
          console.error('Forbidden:', data?.error || 'Access denied')
          break
        case 404:
          console.error('Resource not found')
          break
        case 409:
          // A stale base version on a whole-document write: the caller rebases using data.version.
          break
        case 500:
          console.error('Server error')
          break
        default:
          console.error('Request failed:', data?.error || error.message)
      }
    } else if (error.request) {
      console.error('Network error: No response received')
    } else {
      console.error('Request setup error:', error.message)
    }
    return Promise.reject(error)
  }
)

export default api
