<template>
  <div class="doc-editor">
    <div v-if="editor" class="editor-container">
      <div class="toolbar">
        <div class="toolbar-group">
          <button @click="handleUndo" :disabled="!canUndo" class="toolbar-btn" title="撤销">
            <span>撤销</span>
          </button>
          <button @click="handleRedo" :disabled="!canRedo" class="toolbar-btn" title="重做">
            <span>重做</span>
          </button>
        </div>
        <div class="toolbar-group">
          <button @click="toggleBold" :class="{ active: editor.isActive('bold') }" class="toolbar-btn" title="粗体">
            <strong>B</strong>
          </button>
          <button @click="toggleItalic" :class="{ active: editor.isActive('italic') }" class="toolbar-btn" title="斜体">
            <em>I</em>
          </button>
          <button @click="toggleStrike" :class="{ active: editor.isActive('strike') }" class="toolbar-btn" title="删除线">
            <s>S</s>
          </button>
          <button @click="toggleCode" :class="{ active: editor.isActive('code') }" class="toolbar-btn" title="代码">
            <code>C</code>
          </button>
        </div>
        <div class="toolbar-group">
          <button @click="toggleHistory" class="toolbar-btn" :class="{ active: showHistory }">
            操作历史
          </button>
        </div>
        <div class="toolbar-group">
          <button @click="showShareDialog = true" class="toolbar-btn">
            分享
          </button>
        </div>
        <div class="toolbar-group">
          <button @click="downloadAsDocx" class="toolbar-btn">
            下载
          </button>
        </div>
      </div>
      <div class="editor-content">
        <div class="collaborators-bar">
          <div v-for="cursor in collaborators" :key="cursor.userId" 
               class="collaborator-tag" 
               :style="{ borderColor: cursor.color, backgroundColor: cursor.color + '20' }">
            <span class="collaborator-dot" :style="{ backgroundColor: cursor.color }"></span>
            <span class="collaborator-name">{{ cursor.username }}</span>
          </div>
        </div>
        <editor-content :editor="editor" class="tiptap-editor" />
      </div>
      <div v-if="showHistory" class="history-panel">
        <div class="history-header">
          <h3>操作历史</h3>
          <button @click="showHistory = false" class="close-btn">×</button>
        </div>
        <div class="history-list">
          <div v-if="operationHistory.length === 0" class="empty-history">
            暂无操作记录
          </div>
          <div v-for="(log, index) in operationHistory" :key="log.id" class="history-item">
            <div class="history-info">
              <span class="history-type">{{ log.commandType }}</span>
              <span class="history-user">{{ log.username || '用户' + log.userId }}</span>
              <span class="history-version">v{{ log.version }}</span>
            </div>
            <div class="history-params">{{ log.commandParams }}</div>
            <div class="history-time">{{ formatTime(log.createdAt) }}</div>
            <button @click="restoreVersion(log.version)" class="restore-btn">恢复</button>
          </div>
        </div>
      </div>
      <div v-if="showShareDialog" class="share-dialog">
        <div class="share-dialog-content">
          <h3>分享文档</h3>
          <div class="share-form">
            <input v-model="shareUserCode" type="text" placeholder="输入8位用户ID" class="share-input" maxlength="8" />
            <select v-model="sharePermission" class="share-select">
              <option value="READ_WRITE">可编辑</option>
              <option value="READ_ONLY">只读</option>
            </select>
            <button @click="handleShare" class="share-btn">分享</button>
          </div>
          <div v-if="shareMessage" :class="['share-message', shareSuccess ? 'success' : 'error']">
            {{ shareMessage }}
          </div>
          <div class="share-list">
            <h4>已分享用户</h4>
            <div v-for="share in documentShares" :key="share.id" class="share-item">
              <span>{{ share.username }}</span>
              <span class="share-permission">{{ share.permission === 'READ_WRITE' ? '可编辑' : '只读' }}</span>
              <button @click="removeShare(share.userId)" class="remove-share-btn">×</button>
            </div>
          </div>
          <button @click="showShareDialog = false" class="close-dialog-btn">关闭</button>
        </div>
      </div>
    </div>
    <div v-if="loadError" class="loading">
      <div class="error-state">
        <p>加载文档失败</p>
        <button @click="loadDocument" class="retry-btn">重试</button>
      </div>
    </div>
    <div v-else class="loading">加载中...</div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import api from '../services/api'
import wsService from '../services/websocket'

const props = defineProps({
  documentId: String,
  user: Object
})

const emit = defineEmits(['update-version', 'update-online'])

const isRemoteUpdate = ref(false)
const currentVersion = ref(0)
const showHistory = ref(false)
const operationHistory = ref([])
const canUndo = ref(false)
const canRedo = ref(false)
const collaborators = ref([])
const cursorTimeouts = new Map()
const showShareDialog = ref(false)
const shareUserCode = ref('')
const sharePermission = ref('READ_WRITE')
const shareMessage = ref('')
const shareSuccess = ref(false)
const documentShares = ref([])
const loadError = ref(false)

const cursorColors = ['#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4', '#FFEAA7', '#DDA0DD', '#98D8C8', '#F7DC6F']
const getRandomColor = (userId) => {
  const num = parseInt(userId)
  const index = (isNaN(num) ? 0 : num) % cursorColors.length
  return cursorColors[index]
}

const editor = useEditor({
  extensions: [StarterKit],
  content: '',
  editorProps: {
    handleKeyDown(view, event) {
      if (event.key === 'Tab') {
        event.preventDefault()
        const { from, to } = view.state.selection
        const tr = view.state.tr.insertText('\t', from, to)
        view.dispatch(tr)
        return true
      }
      return false
    }
  },
  onUpdate: ({ editor }) => {
    if (isRemoteUpdate.value) return
    const { from } = editor.state.selection
    sendCursorPosition(from)
    debouncedSave(editor.getHTML())
  },
  onSelectionUpdate: ({ editor }) => {
    const { from } = editor.state.selection
    sendCursorPosition(from)
  }
})

const saveDocument = async (content) => {
  try {
    const response = await api.put(`/api/documents/${props.documentId}`, {
      content: content,
      version: currentVersion.value,
      userId: props.user.id
    })
    if (response.data && response.data.version !== undefined) {
      currentVersion.value = response.data.version
      emit('update-version', response.data.version)
    }
  } catch (error) {
    console.error('Failed to save document:', error)
  }
}

let saveTimeout = null
const debouncedSave = (content) => {
  if (saveTimeout) clearTimeout(saveTimeout)
  saveTimeout = setTimeout(() => saveDocument(content), 1000)
}

const sendCursorPosition = (position) => {
  wsService.send({
    type: 'CURSOR',
    documentId: props.documentId,
    userId: props.user.id,
    position: position
  })
}

const handleUndo = async () => {
  try {
    const response = await api.post(`/api/documents/${props.documentId}/undo?userId=${props.user.id}`)
    if (response.data.error) {
      console.log('Undo:', response.data.error)
      return
    }
    const state = response.data
    isRemoteUpdate.value = true
    if (editor.value && state.content !== undefined) {
      editor.value.commands.setContent(state.content)
    }
    currentVersion.value = state.version
    emit('update-version', state.version)
    canUndo.value = state.version > 0
    canRedo.value = true
    isRemoteUpdate.value = false
  } catch (error) {
    console.error('Undo failed:', error)
  }
}

const handleRedo = async () => {
  try {
    const response = await api.post(`/api/documents/${props.documentId}/redo?userId=${props.user.id}`)
    if (response.data.error) {
      console.log('Redo:', response.data.error)
      canRedo.value = false
      return
    }
    const state = response.data
    isRemoteUpdate.value = true
    if (editor.value && state.content !== undefined) {
      editor.value.commands.setContent(state.content)
    }
    currentVersion.value = state.version
    emit('update-version', state.version)
    canUndo.value = true
    isRemoteUpdate.value = false
  } catch (error) {
    console.error('Redo failed:', error)
    canRedo.value = false
  }
}

const toggleBold = () => {
  if (editor.value) editor.value.chain().focus().toggleBold().run()
}

const toggleItalic = () => {
  if (editor.value) editor.value.chain().focus().toggleItalic().run()
}

const toggleStrike = () => {
  if (editor.value) editor.value.chain().focus().toggleStrike().run()
}

const toggleCode = () => {
  if (editor.value) editor.value.chain().focus().toggleCode().run()
}

const toggleHistory = async () => {
  showHistory.value = !showHistory.value
  if (showHistory.value) {
    await loadOperationHistory()
  }
}

const loadOperationHistory = async () => {
  try {
    const response = await api.get(`/api/documents/${props.documentId}/history?userId=${props.user.id}`)
    operationHistory.value = response.data
  } catch (error) {
    console.error('Failed to load operation history:', error)
  }
}

const restoreVersion = async (version) => {
  try {
    const response = await api.post(`/api/documents/${props.documentId}/restore/${version}?userId=${props.user.id}`)
    if (response.data.error) {
      console.error('Restore failed:', response.data.error)
      return
    }
    const state = response.data
    isRemoteUpdate.value = true
    if (editor.value && state.content !== undefined) {
      editor.value.commands.setContent(state.content)
    }
    currentVersion.value = state.version
    emit('update-version', state.version)
    isRemoteUpdate.value = false
    showHistory.value = false
  } catch (error) {
    console.error('Restore failed:', error)
  }
}

const formatTime = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

const downloadAsDocx = () => {
  if (!editor.value) return
  const html = editor.value.getHTML()
  const fullHtml = [
    '<html xmlns:o="urn:schemas-microsoft-com:office:office"',
    '      xmlns:w="urn:schemas-microsoft-com:office:word"',
    '      xmlns="http://www.w3.org/TR/REC-html40">',
    '<head><meta charset="utf-8">',
    '<style>body { font-family: SimSun, serif; font-size: 12pt; }',
    'h1, h2, h3 { font-family: SimHei, sans-serif; }',
    'p { margin: 0.5em 0; }</style>',
    '</head><body>' + html + '</body></html>'
  ].join('\n')
  const blob = new Blob([fullHtml], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'document.docx'
  a.click()
  URL.revokeObjectURL(url)
}

const handleShare = async () => {
  try {
    shareMessage.value = ''
    
    const userResponse = await api.get(`/api/users/lookup?userCode=${shareUserCode.value}`)
    if (userResponse.data.error) {
      shareMessage.value = '用户不存在'
      shareSuccess.value = false
      return
    }
    
    const targetUserId = userResponse.data.id
    
    const response = await api.post(`/api/documents/${props.documentId}/share`, {
      userId: targetUserId,
      sharedByUserId: props.user.id,
      permission: sharePermission.value
    })
    if (response.data.error) {
      shareMessage.value = response.data.error
      shareSuccess.value = false
    } else {
      shareMessage.value = '分享成功'
      shareSuccess.value = true
      shareUserCode.value = ''
      await loadDocumentShares()
    }
  } catch (error) {
    shareMessage.value = error.response?.data?.error || '分享失败'
    shareSuccess.value = false
  }
}

const removeShare = async (userId) => {
  try {
    await api.delete(`/api/documents/${props.documentId}/share/${userId}?requestUserId=${props.user.id}`)
    await loadDocumentShares()
  } catch (error) {
    console.error('Failed to remove share:', error)
  }
}

const loadDocumentShares = async () => {
  try {
    const response = await api.get(`/api/documents/${props.documentId}/shares`)
    documentShares.value = response.data
  } catch (error) {
    console.error('Failed to load document shares:', error)
  }
}

const connectWebSocket = async () => {
  try {
    await wsService.connect(props.documentId, String(props.user.id))

    wsService.on('INIT', (message) => {
      isRemoteUpdate.value = true
      if (editor.value && message.content) {
        editor.value.commands.setContent(message.content)
      }
      currentVersion.value = message.version
      emit('update-version', message.version)
      emit('update-online', message.onlineCount)
      canUndo.value = message.version > 0
      isRemoteUpdate.value = false
    })

    wsService.on('CONTENT_UPDATE', (message) => {
      if (saveTimeout) {
        clearTimeout(saveTimeout)
        saveTimeout = null
      }
      isRemoteUpdate.value = true
      if (editor.value && message.content) {
        editor.value.commands.setContent(message.content)
      }
      currentVersion.value = message.version
      emit('update-version', message.version)
      if (message.onlineCount !== undefined) {
        emit('update-online', message.onlineCount)
      }
      canUndo.value = message.version > 0
      isRemoteUpdate.value = false
    })

    wsService.on('CURSOR_UPDATE', (message) => {
      const userId = message.userId
      const position = message.position
      
      const existingIndex = collaborators.value.findIndex(c => c.userId === userId)
      if (existingIndex >= 0) {
        collaborators.value[existingIndex].position = position
      } else {
        collaborators.value.push({
          userId: userId,
          username: `用户${userId}`,
          position: position,
          color: getRandomColor(userId)
        })
      }
      
      if (cursorTimeouts.has(userId)) {
        clearTimeout(cursorTimeouts.get(userId))
      }
      cursorTimeouts.set(userId, setTimeout(() => {
        collaborators.value = collaborators.value.filter(c => c.userId !== userId)
        cursorTimeouts.delete(userId)
      }, 5000))
    })

    wsService.on('USER_LEFT', (message) => {
      emit('update-online', message.onlineCount)
    })
  } catch (error) {
    console.error('WebSocket connection failed:', error)
  }
}

const loadDocument = async () => {
  loadError.value = false
  try {
    const response = await api.get(`/api/documents/${props.documentId}?userId=${props.user.id}`)
    currentVersion.value = response.data.version
    emit('update-version', response.data.version)
    if (editor.value && response.data.content) {
      editor.value.commands.setContent(response.data.content)
    }
    canUndo.value = response.data.version > 0
  } catch (error) {
    console.error('Failed to load document:', error)
    loadError.value = true
  }
}

onMounted(async () => {
  await loadDocument()
  await connectWebSocket()
  await loadDocumentShares()
})

onBeforeUnmount(() => {
  wsService.disconnect()
  if (editor.value) {
    editor.value.destroy()
  }
  if (saveTimeout) clearTimeout(saveTimeout)
})
</script>

<style scoped>
.doc-editor {
  width: 100%;
  max-width: 800px;
}

.editor-container {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background: #f8f9fa;
  border-bottom: 1px solid #e9ecef;
  gap: 8px;
  flex-wrap: wrap;
}

.toolbar-group {
  display: flex;
  gap: 4px;
}

.toolbar-btn {
  padding: 6px 12px;
  background: white;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  color: #495057;
  transition: all 0.2s;
}

.toolbar-btn:hover:not(:disabled) {
  background: #e9ecef;
  border-color: #ced4da;
}

.toolbar-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toolbar-btn.active {
  background: #667eea;
  color: white;
  border-color: #667eea;
}

.editor-content {
  min-height: 500px;
  position: relative;
}

.collaborators-bar {
  display: flex;
  gap: 8px;
  padding: 8px 20px;
  flex-wrap: wrap;
  min-height: 24px;
}

.collaborator-tag {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 12px;
  border: 1px solid;
  font-size: 12px;
}

.collaborator-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.collaborator-name {
  color: #555;
}

.tiptap-editor {
  padding: 20px;
  min-height: 500px;
  outline: none;
}

.tiptap-editor :deep(.tiptap) {
  outline: none;
  min-height: 460px;
}

.tiptap-editor :deep(.tiptap p) {
  margin-bottom: 0.75em;
}

.tiptap-editor :deep(.tiptap h1),
.tiptap-editor :deep(.tiptap h2),
.tiptap-editor :deep(.tiptap h3) {
  margin-top: 1em;
  margin-bottom: 0.5em;
}

.history-panel {
  border-top: 1px solid #e9ecef;
  background: #f8f9fa;
  max-height: 300px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e9ecef;
}

.history-header h3 {
  margin: 0;
  font-size: 16px;
  color: #333;
}

.close-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: #666;
  padding: 0;
  line-height: 1;
}

.close-btn:hover {
  color: #333;
}

.history-list {
  overflow-y: auto;
  padding: 8px;
}

.empty-history {
  text-align: center;
  color: #999;
  padding: 20px;
}

.history-item {
  background: white;
  border: 1px solid #e9ecef;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}

.history-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.history-type {
  font-weight: 600;
  color: #667eea;
}

.history-user {
  font-size: 12px;
  color: #666;
  background: #f0f0f0;
  padding: 2px 6px;
  border-radius: 3px;
}

.history-version {
  font-size: 12px;
  color: #999;
  background: #f0f0f0;
  padding: 2px 6px;
  border-radius: 3px;
}

.history-params {
  font-size: 12px;
  color: #666;
  margin-bottom: 4px;
  word-break: break-all;
}

.history-time {
  font-size: 11px;
  color: #999;
  margin-bottom: 6px;
}

.restore-btn {
  padding: 4px 8px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
}

.restore-btn:hover {
  background: #5a6fd6;
}

.share-dialog {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.share-dialog-content {
  background: white;
  padding: 24px;
  border-radius: 8px;
  width: 400px;
  max-height: 80vh;
  overflow-y: auto;
}

.share-dialog-content h3 {
  margin: 0 0 16px 0;
  color: #333;
}

.share-form {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.share-input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}

.share-select {
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}

.share-btn {
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.share-btn:hover {
  background: #5a6fd6;
}

.share-message {
  padding: 8px 12px;
  border-radius: 4px;
  margin-bottom: 16px;
  font-size: 14px;
}

.share-message.success {
  background: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.share-message.error {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

.share-list h4 {
  margin: 0 0 8px 0;
  color: #333;
  font-size: 14px;
}

.share-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f8f9fa;
  border-radius: 4px;
  margin-bottom: 8px;
}

.share-permission {
  font-size: 12px;
  color: #666;
  background: white;
  padding: 2px 6px;
  border-radius: 3px;
}

.remove-share-btn {
  background: none;
  border: none;
  color: #e74c3c;
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
}

.remove-share-btn:hover {
  color: #c0392b;
}

.close-dialog-btn {
  width: 100%;
  padding: 10px;
  background: #6c757d;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  margin-top: 16px;
}

.close-dialog-btn:hover {
  background: #5a6268;
}

.loading {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 200px;
  color: #999;
}

.error-state {
  text-align: center;
}

.error-state p {
  margin-bottom: 12px;
  color: #e74c3c;
}

.retry-btn {
  padding: 6px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.retry-btn:hover {
  background: #5a6fd6;
}
</style>
