<template>
  <div class="admin-view">
    <div class="admin-header">
      <h1>用户管理</h1>
      <button @click="$emit('back')" class="back-btn">返回</button>
    </div>

    <div class="search-bar">
      <input v-model="keyword" type="text" placeholder="搜索用户名或邮箱..." class="search-input" @input="debounceSearch" />
    </div>

    <div class="stats-bar">
      <span>共 {{ totalElements }} 位用户</span>
    </div>

    <div v-if="loading" class="empty">加载中...</div>

    <div v-else class="user-table">
      <div class="table-header">
        <div class="col-id">ID</div>
        <div class="col-code">用户编码</div>
        <div class="col-username">用户名</div>
        <div class="col-email">邮箱</div>
        <div class="col-role">角色</div>
        <div class="col-status">状态</div>
        <div class="col-created">创建时间</div>
        <div class="col-actions">操作</div>
      </div>
      <div v-for="user in users" :key="user.id" class="table-row">
        <div class="col-id">{{ user.id }}</div>
        <div class="col-code">{{ user.userCode }}</div>
        <div class="col-username">{{ user.username }}</div>
        <div class="col-email">{{ user.email }}</div>
        <div class="col-role">
          <span :class="['role-badge', user.role === 'ADMIN' ? 'admin' : 'user']">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
        </div>
        <div class="col-status">
          <span :class="['status-badge', user.enabled ? 'active' : 'disabled']">{{ user.enabled ? '启用' : '禁用' }}</span>
        </div>
        <div class="col-created">{{ formatDate(user.createdAt) }}</div>
        <div class="col-actions">
          <button v-if="user.id !== currentUserId" class="action-btn role-btn" @click="toggleRole(user)">
            {{ user.role === 'ADMIN' ? '设为用户' : '设为管理员' }}
          </button>
          <button v-if="user.id !== currentUserId" class="action-btn toggle-btn" @click="toggleEnabled(user)">
            {{ user.enabled ? '禁用' : '启用' }}
          </button>
          <button v-if="user.id !== currentUserId" class="action-btn reset-btn" @click="resetPassword(user)">重置密码</button>
          <button v-if="user.id !== currentUserId" class="action-btn delete-btn" @click="deleteUser(user)">删除</button>
        </div>
      </div>
      <div v-if="users.length === 0" class="empty">暂无用户数据</div>
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="currentPage === 0" @click="changePage(currentPage - 1)">上一页</button>
      <span>{{ currentPage + 1 }} / {{ totalPages }}</span>
      <button :disabled="currentPage >= totalPages - 1" @click="changePage(currentPage + 1)">下一页</button>
    </div>

    <div v-if="newPassword" class="modal-overlay" @click="newPassword = null">
      <div class="modal" @click.stop>
        <h3>密码已重置</h3>
        <p>新密码为: <strong>{{ newPassword }}</strong></p>
        <p class="hint">请将此密码安全地告知用户。</p>
        <button @click="newPassword = null" class="modal-close-btn">确定</button>
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

const emit = defineEmits(['back'])

const currentUserId = ref(props.user.id)
const users = ref([])
const loading = ref(true)
const keyword = ref('')
const currentPage = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const newPassword = ref(null)
let searchTimer = null

const loadUsers = async () => {
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: 10
    }
    if (keyword.value.trim()) {
      params.keyword = keyword.value.trim()
    }
    const response = await api.get('/api/admin/users', { params })
    users.value = response.data.content
    totalPages.value = response.data.totalPages
    totalElements.value = response.data.totalElements
  } catch (error) {
    console.error('Failed to load users:', error)
    alert('加载用户列表失败: ' + (error.response?.data?.error || error.message))
  } finally {
    loading.value = false
  }
}

const debounceSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 0
    loadUsers()
  }, 300)
}

const changePage = (page) => {
  currentPage.value = page
  loadUsers()
}

const toggleRole = async (user) => {
  const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
  if (!confirm(`确定将 ${user.username} 的角色改为 ${newRole === 'ADMIN' ? '管理员' : '普通用户'} 吗？`)) return
  try {
    await api.put(`/api/admin/users/${user.id}/role`, { role: newRole })
    await loadUsers()
  } catch (error) {
    alert('操作失败: ' + (error.response?.data?.error || error.message))
  }
}

const toggleEnabled = async (user) => {
  const newEnabled = !user.enabled
  if (!confirm(`确定${newEnabled ? '启用' : '禁用'}用户 ${user.username} 吗？`)) return
  try {
    await api.put(`/api/admin/users/${user.id}/enabled`, { enabled: newEnabled })
    await loadUsers()
  } catch (error) {
    alert('操作失败: ' + (error.response?.data?.error || error.message))
  }
}

const resetPassword = async (user) => {
  if (!confirm(`确定重置 ${user.username} 的密码吗？`)) return
  try {
    const response = await api.put(`/api/admin/users/${user.id}/password`, {})
    newPassword.value = response.data.newPassword
  } catch (error) {
    alert('操作失败: ' + (error.response?.data?.error || error.message))
  }
}

const deleteUser = async (user) => {
  if (!confirm(`确定删除用户 ${user.username} 吗？此操作不可恢复！`)) return
  try {
    await api.delete(`/api/admin/users/${user.id}`)
    await loadUsers()
  } catch (error) {
    alert('删除失败: ' + (error.response?.data?.error || error.message))
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString('zh-CN')
}

onMounted(loadUsers)
</script>

<style scoped>
.admin-view {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.admin-header h1 {
  font-size: 24px;
  color: #333;
}

.back-btn {
  padding: 8px 16px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.back-btn:hover {
  background: #5a6fd6;
}

.search-bar {
  margin-bottom: 16px;
}

.search-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}

.search-input:focus {
  outline: none;
  border-color: #667eea;
}

.stats-bar {
  margin-bottom: 12px;
  font-size: 14px;
  color: #666;
}

.user-table {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  overflow-x: auto;
}

.table-header, .table-row {
  display: grid;
  grid-template-columns: 50px 90px 100px 1fr 80px 70px 140px 200px;
  align-items: center;
  padding: 12px 16px;
  font-size: 13px;
}

.table-header {
  background: #f8f9fa;
  font-weight: 600;
  color: #555;
  border-bottom: 2px solid #eee;
  border-radius: 8px 8px 0 0;
}

.table-row {
  border-bottom: 1px solid #f0f0f0;
  transition: background 0.2s;
}

.table-row:hover {
  background: #f8f9fa;
}

.table-row:last-child {
  border-bottom: none;
}

.role-badge {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}

.role-badge.admin {
  background: #667eea;
  color: white;
}

.role-badge.user {
  background: #e8eaf6;
  color: #555;
}

.status-badge {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}

.status-badge.active {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-badge.disabled {
  background: #fce4ec;
  color: #c62828;
}

.col-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.action-btn {
  padding: 3px 8px;
  border: none;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.role-btn {
  background: #e3f2fd;
  color: #1565c0;
}

.role-btn:hover {
  background: #bbdefb;
}

.toggle-btn {
  background: #fff3e0;
  color: #e65100;
}

.toggle-btn:hover {
  background: #ffe0b2;
}

.reset-btn {
  background: #f3e5f5;
  color: #7b1fa2;
}

.reset-btn:hover {
  background: #e1bee7;
}

.delete-btn {
  background: #ffebee;
  color: #c62828;
}

.delete-btn:hover {
  background: #ffcdd2;
}

.empty {
  text-align: center;
  color: #999;
  padding: 40px;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
}

.pagination button {
  padding: 8px 16px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 4px;
  cursor: pointer;
}

.pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.pagination button:not(:disabled):hover {
  background: #f5f5f5;
}

.modal-overlay {
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

.modal {
  background: white;
  padding: 24px;
  border-radius: 8px;
  max-width: 400px;
  width: 90%;
}

.modal h3 {
  margin-bottom: 12px;
  color: #333;
}

.modal p {
  margin-bottom: 8px;
  color: #555;
}

.modal .hint {
  font-size: 12px;
  color: #999;
}

.modal-close-btn {
  margin-top: 12px;
  padding: 8px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
</style>
