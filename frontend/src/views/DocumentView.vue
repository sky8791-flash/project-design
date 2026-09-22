<template>
  <div class="document-view">
    <div class="toolbar">
      <button @click="$emit('back')" class="back-btn">← 返回</button>
      <div class="doc-info">
        <span class="online-count">{{ onlineCount }} 人在线</span>
      </div>
    </div>
    <div class="editor-area">
      <DocEditor
        :document-id="documentId"
        :user="user"
        @update-online="onlineCount = $event"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DocEditor from '../components/DocEditor.vue'

defineProps({
  documentId: String,
  user: Object
})

defineEmits(['back'])

const onlineCount = ref(1)
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

.doc-info {
  margin-left: auto;
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
