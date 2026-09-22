<template>
  <div class="document-view">
    <div class="toolbar">
      <button @click="$emit('back')" class="back-btn">← 返回</button>
      <div class="doc-title">
        <input
          v-if="editingTitle"
          v-model="draftTitle"
          class="title-input"
          maxlength="120"
          @keyup.enter="saveTitle"
          @keyup.esc="editingTitle = false"
        />
        <h2 v-else :title="title || '未命名文档'" @click="startEditing">{{ title || '未命名文档' }}</h2>
      </div>
      <div class="doc-info">
        <span class="online-count">{{ onlineCount }} 人在线</span>
      </div>
    </div>
    <div class="editor-area">
      <DocEditor
        :document-id="documentId"
        :user="user"
        @update-online="onlineCount = $event"
        @update-title="onTitle"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DocEditor from '../components/DocEditor.vue'
import api from '../services/api'

const props = defineProps({
  documentId: String,
  user: Object
})

defineEmits(['back'])

const onlineCount = ref(1)
const title = ref('')
const editingTitle = ref(false)
const draftTitle = ref('')

const onTitle = (value) => { title.value = value }

const startEditing = () => {
  if (props.user?.id == null) return
  draftTitle.value = title.value
  editingTitle.value = true
}

const saveTitle = async () => {
  const next = draftTitle.value.trim()
  editingTitle.value = false
  if (!next || next === title.value) return
  try {
    await api.put(`/api/documents/${props.documentId}/title`, { title: next })
    title.value = next
  } catch (error) {
    console.error('Failed to rename document:', error)
    alert('重命名失败: ' + (error.response?.data?.error || error.message))
  }
}
</script>

<style scoped>
.document-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 20px;
  background: white;
  border-bottom: 1px solid #eee;
}

.back-btn {
  padding: 6px 12px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
}

.back-btn:hover {
  background: #e9ecef;
}

.doc-title {
  flex: 1;
  min-width: 0;
}

.doc-title h2 {
  font-size: 15px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: text;
}

.title-input {
  width: 100%;
  max-width: 360px;
  padding: 4px 8px;
  font-size: 15px;
  border: 1px solid #ced4da;
  border-radius: 4px;
}

.doc-info {
  display: flex;
  gap: 16px;
  font-size: 14px;
  color: #666;
}

.editor-area {
  flex: 1;
  padding: 20px;
  display: flex;
  justify-content: center;
  overflow: auto;
}
</style>
