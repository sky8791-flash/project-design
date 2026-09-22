import { readToken } from './api'

const MAX_QUEUED_FRAMES = 200
// Frames that are worthless once superseded, so they never occupy the reconnect queue.
const EPHEMERAL = new Set(['CURSOR'])

class WebSocketService {
  constructor() {
    this.ws = null
    this.handlers = new Map()
    this.queue = []
    this.documentId = null
    this.token = null
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = 5
    this.reconnectDelay = 1000
    this.isConnecting = false
    this.closedByUser = false
    this.settle = null
  }

  connect(documentId) {
    this.documentId = documentId
    this.token = readToken()
    this.reconnectAttempts = 0
    this.closedByUser = false

    return new Promise((resolve, reject) => {
      this.settle = { resolve, reject }
      this._connect()
    })
  }

  _connect() {
    if (this.isConnecting || this.closedByUser) return
    // Re-read per attempt: a reconnect after a long outage may find a renewed token, and an expired one
    // must fail here rather than after five retries against a credential the server will never accept.
    const token = readToken()
    if (!token) {
      this._reject(new Error('Session expired, please sign in again'))
      return
    }
    this.isConnecting = true
    if (this.ws) {
      // A socket from a previous attempt would otherwise keep firing handlers we no longer own.
      const stale = this.ws
      this.ws = null
      stale.onclose = stale.onerror = stale.onmessage = stale.onopen = null
      try { stale.close() } catch { /* already gone */ }
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    // A browser cannot set an Authorization header on a WebSocket handshake, so the token rides in the
    // query string and the server turns it into a session attribute before the handler runs.
    const wsUrl = `${protocol}//${window.location.host}/ws/document/${this.documentId}?token=${encodeURIComponent(token)}`

    this.ws = new WebSocket(wsUrl)

    this.ws.onopen = () => {
      this.isConnecting = false
      this.reconnectAttempts = 0
      this._drainQueue()
      if (this.settle) {
        this.settle.resolve()
        this.settle = null
      }
    }

    this.ws.onmessage = (event) => {
      let message
      try {
        message = JSON.parse(event.data)
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
        return
      }
      const list = this.handlers.get(message.type)
      if (!list) return
      // Copy first: a handler may unsubscribe while we are iterating.
      ;[...list].forEach((handler) => handler(message))
    }

    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error)
      this.isConnecting = false
      this._reject(error)
    }

    this.ws.onclose = (event) => {
      console.log('WebSocket disconnected', event.code, event.reason)
      this.isConnecting = false

      if (event.code >= 4000 && event.code < 5000) {
        // The server refused the session (no access, bad handshake): retrying cannot help.
        this._reject(new Error(`WebSocket closed by server: ${event.reason || event.code}`))
        return
      }
      if (this.closedByUser) {
        this._reject(new Error('WebSocket closed'))
        return
      }
      if (this.reconnectAttempts >= this.maxReconnectAttempts) {
        this._reject(new Error('WebSocket reconnect attempts exhausted'))
        return
      }
      this._reconnect()
    }
  }

  _reject(error) {
    if (!this.settle) return
    const { reject } = this.settle
    this.settle = null
    reject(error)
  }

  _reconnect() {
    this.reconnectAttempts++
    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1)
    console.log(`Attempting to reconnect (${this.reconnectAttempts}/${this.maxReconnectAttempts}) in ${delay}ms...`)
    setTimeout(() => {
      if (this.documentId && this.token && !this.closedByUser) this._connect()
    }, delay)
  }

  /** @returns {() => void} unsubscribe */
  on(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, [])
    const list = this.handlers.get(type)
    list.push(handler)
    return () => {
      const current = this.handlers.get(type)
      if (!current) return
      const index = current.indexOf(handler)
      if (index >= 0) current.splice(index, 1)
    }
  }

  send(message) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
      return true
    }
    if (EPHEMERAL.has(message.type) || this.queue.length >= MAX_QUEUED_FRAMES) return false
    // Keystrokes typed while offline are replayed after reconnect; a stale base is rejected by the
    // server, which tells this client the real version so it can rebase and retry.
    this.queue.push(message)
    return false
  }

  _drainQueue() {
    const queued = this.queue
    this.queue = []
    queued.forEach((message) => this.send(message))
  }

  disconnect() {
    this.closedByUser = true
    this.queue = []
    this.handlers.clear()
    if (this.ws) {
      this.ws.close(1000, 'User disconnected')
      this.ws = null
    }
  }

  isConnected() {
    return Boolean(this.ws && this.ws.readyState === WebSocket.OPEN)
  }
}

export default new WebSocketService()
