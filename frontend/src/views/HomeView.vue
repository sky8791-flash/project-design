<template>
  <div class="home-view">
    <div class="header">
      <h1>文档列表</h1>
      <div class="user-info">
        <div class="user-detail">
          <span class="username">{{ user.username }}</span>
          <span class="user-code">ID: {{ user.userCode }}</span>
        </div>
        <button v-if="user.role === 'ADMIN'" @click="$emit('go-admin')" class="admin-btn">管理</button>
        <button @click="$emit('logout')" class="logout-btn">退出</button>
      </div>
    </div>
    <div class="actions">
      <input v-model="newDocTitle" type="text" placeholder="新文档标题..." class="new-doc-input" />
      <button @click="handleCreate" class="create-btn">创建文档</button>
    </div>
    <div class="tabs">
      <button :class="{ active: activeTab === 'my' }" @click="activeTab = 'my'">我的文档</button>
      <button :class="{ active: activeTab === 'shared' }" @click="activeTab = 'shared'; loadSharedDocuments()">共享文档</button>
    </div>
    <div class="document-list">
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="activeTab === 'my' && documents.length === 0" class="empty">暂无文档，点击上方按钮创建</div>
      <div v-else-if="activeTab === 'shared' && sharedDocuments.length === 0" class="empty">暂无共享文档</div>
      <div v-for="doc in (activeTab === 'my' ? documents : sharedDocuments)" :key="doc.id" class="document-item">
        <div class="doc-content" @click="$emit('open-document', String(doc.id))">
          <div class="doc-title">{{ doc.title }}</div>
          <div class="doc-meta">
            <span>版本: {{ doc.version }}</span>
            <span>更新: {{ formatDate(doc.updatedAt) }}</span>
            <span v-if="activeTab === 'shared'" class="shared-badge">共享</span>
          </div>
        </div>
        <div v-if="activeTab === 'my'" class="doc-actions">
          <button @click.stop="deleteDocument(doc.id)" class="delete-btn" title="删除文档">×</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../services/api'

const props = defineProps({
  user: Object
})

const emit = defineEmits(['open-document', 'create-document', 'logout', 'go-admin'])

const documents = ref([])
const sharedDocuments = ref([])
const newDocTitle = ref('')
const activeTab = ref('my')
const loading = ref(true)

const loadDocuments = async () => {
  loading.value = true
  try {
    const response = await api.get(`/api/documents/user/${props.user.id}`)
    documents.value = response.data
  } catch (error) {
    console.error('Failed to load documents:', error)
  } finally {
    loading.value = false
  }
}

const loadSharedDocuments = async () => {
  try {
    const response = await api.get(`/api/documents/shared/${props.user.id}`)
    sharedDocuments.value = response.data
  } catch (error) {
    console.error('Failed to load shared documents:', error)
  }
}

const handleCreate = () => {
  if (newDocTitle.value.trim()) {
    emit('create-document', newDocTitle.value.trim())
    newDocTitle.value = ''
  }
}

const deleteDocument = async (docId) => {
  if (!confirm('确定要删除这个文档吗？')) return
  
  try {
    await api.delete(`/api/documents/${docId}?userId=${props.user.id}`)
    await loadDocuments()
  } catch (error) {
    console.error('Failed to delete document:', error)
    alert('删除失败: ' + (error.response?.data?.error || error.message))
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

onMounted(loadDocuments)
</script>

<style scoped>
.home-view {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-detail {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.username {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.user-code {
  font-size: 12px;
  color: #999;
}

.logout-btn {
  padding: 6px 12px;
  background: #e74c3c;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.admin-btn {
  padding: 6px 12px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.admin-btn:hover {
  background: #5a6fd6;
}

.logout-btn:hover {
  background: #c0392b;
}

.actions {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.new-doc-input {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}

.new-doc-input:focus {
  outline: none;
  border-color: #667eea;
}

.create-btn {
  padding: 10px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.create-btn:hover {
  background: #5a6fd6;
}

.tabs {
  display: flex;
  gap: 0;
  margin-bottom: 20px;
  border-bottom: 2px solid #eee;
}

.tabs button {
  flex: 1;
  padding: 10px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  color: #666;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
}

.tabs button.active {
  color: #667eea;
  border-bottom-color: #667eea;
}

.tabs button:hover {
  color: #333;
}

.document-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.empty {
  text-align: center;
  color: #999;
  padding: 40px;
}

.document-item {
  display: flex;
  align-items: center;
  padding: 16px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  transition: transform 0.2s;
}

.document-item:hover {
  transform: translateY(-2px);
}

.doc-content {
  flex: 1;
  cursor: pointer;
}

.doc-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
  color: #333;
}

.doc-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #999;
}

.shared-badge {
  background: #667eea;
  color: white;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
}

.doc-actions {
  margin-left: 12px;
}

.delete-btn {
  background: none;
  border: none;
  color: #e74c3c;
  font-size: 20px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.2s;
}

.delete-btn:hover {
  background: #f8d7da;
}
</style>
