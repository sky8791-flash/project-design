class WebSocketService {
  constructor() {
    this.ws = null
    this.handlers = {}
    this.documentId = null
    this.userId = null
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = 5
    this.reconnectDelay = 1000
    this.isConnecting = false
  }

  connect(documentId, userId) {
    this.documentId = documentId
    this.userId = userId
    this.reconnectAttempts = 0

    return new Promise((resolve, reject) => {
      this._connect(resolve, reject)
    })
  }

  _connect(resolve, reject) {
    if (this.isConnecting) return
    this.isConnecting = true

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/ws/document/${this.documentId}?userId=${this.userId}`

    this.ws = new WebSocket(wsUrl)

    this.ws.onopen = () => {
      console.log('WebSocket connected')
      this.isConnecting = false
      this.reconnectAttempts = 0
      if (resolve) resolve()
    }

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        const handler = this.handlers[message.type]
        if (handler) {
          handler(message)
        }
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error)
      this.isConnecting = false
      if (reject) reject(error)
    }

    this.ws.onclose = (event) => {
      console.log('WebSocket disconnected', event.code, event.reason)
      this.isConnecting = false
      
      if (!event.wasClean && this.reconnectAttempts < this.maxReconnectAttempts) {
        this._reconnect()
      }
    }
  }

  _reconnect() {
    this.reconnectAttempts++
    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1)
    console.log(`Attempting to reconnect (${this.reconnectAttempts}/${this.maxReconnectAttempts}) in ${delay}ms...`)
    
    setTimeout(() => {
      if (this.documentId && this.userId) {
        this._connect(null, null)
      }
    }, delay)
  }

  on(type, handler) {
    this.handlers[type] = handler
  }

  send(message) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
      return true
    }
    return false
  }

  disconnect() {
    this.reconnectAttempts = this.maxReconnectAttempts
    if (this.ws) {
      this.ws.close(1000, 'User disconnected')
      this.ws = null
    }
  }

  isConnected() {
    return this.ws && this.ws.readyState === WebSocket.OPEN
  }
}

export default new WebSocketService()
