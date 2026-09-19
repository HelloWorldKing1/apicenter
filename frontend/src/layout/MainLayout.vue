<template>
  <el-container class="layout">
    <!-- 侧边导航（视觉与 API中心原型.html 侧边栏对齐：200px / #1d2129 / 圆点 logo / 几何图标 / 底部脚注） -->
    <el-aside width="200px" class="aside">
      <div class="logo"><span class="logo-dot"></span>API 中心</div>
      <el-menu :default-active="$route.path" router class="menu">
        <el-menu-item index="/dashboard"><span class="ico">◧</span>概览</el-menu-item>
        <el-menu-item index="/apps"><span class="ico">▤</span>应用管理</el-menu-item>
        <el-menu-item index="/groups"><span class="ico">▦</span>分组管理</el-menu-item>
        <el-menu-item index="/interfaces"><span class="ico">⇄</span>接口管理</el-menu-item>
        <el-menu-item index="/monitor"><span class="ico">◎</span>接口监控</el-menu-item>
        <el-menu-item index="/adapters"><span class="ico">⚙</span>适配器</el-menu-item>
        <el-menu-item index="/users"><span class="ico">◈</span>账号管理</el-menu-item>
      </el-menu>
      <div class="foot">API 中心 · 管理控制台</div>
    </el-aside>

    <el-container>
      <!-- 顶部栏（原型 .topbar：56px / 白底 / 下边框 / 面包屑 + 标题） -->
      <el-header class="header">
        <span class="crumb">管理面 / </span>
        <span class="title">{{ $route.meta.title }}</span>
        <!-- 账号区（2026-09-18 账号登录）：显示名 + 修改密码 / 退出登录 -->
        <div class="account">
          <el-dropdown trigger="click" @command="onAccountCommand">
            <span class="account-name">
              <span class="avatar">{{ initial }}</span>{{ displayName || '未登录' }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 修改密码（改密成功后后端吊销其他会话，当前会话保留） -->
    <el-dialog v-model="pwd.visible" title="修改密码" width="420px">
      <el-form label-position="top">
        <el-form-item label="原密码">
          <el-input v-model="pwd.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwd.newPassword" type="password" show-password placeholder="8-64 位，含字母与数字"
                    autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwd.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwd.visible = false">取消</el-button>
        <el-button type="primary" :loading="pwd.loading" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import { authStore, passwordIssue } from '@/utils/auth.mjs'

// 主布局：侧边导航 + 顶部栏（含账号区）+ 页面出口
// 视觉口径来自 doc/API中心原型.html（侧边栏 #1d2129 / 主色 #2f54eb / 页面内边距 24px）
const router = useRouter()
const displayName = computed(() => authStore.displayName())
const initial = computed(() => (displayName.value || '?').slice(0, 1).toUpperCase())

const pwd = reactive({ visible: false, oldPassword: '', newPassword: '', confirm: '', loading: false })

function onAccountCommand(command) {
  if (command === 'password') {
    pwd.oldPassword = ''
    pwd.newPassword = ''
    pwd.confirm = ''
    pwd.visible = true
    return
  }
  if (command === 'logout') {
    logout()
  }
}

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录？', '退出登录', { type: 'warning' })
  } catch (e) {
    return // 取消
  }
  try {
    await http.post('/auth/logout')
  } catch (e) {
    // 令牌可能已过期：本地清掉即可，不再打扰用户
  }
  authStore.clear()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

async function submitPassword() {
  if (!pwd.oldPassword) {
    ElMessage.warning('请输入原密码')
    return
  }
  const issue = passwordIssue(pwd.newPassword)
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  if (pwd.newPassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwd.loading = true
  try {
    await http.post('/auth/password', { oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改（其他设备的登录已失效）')
    pwd.visible = false
  } catch (e) {
    // 提示由 http 拦截器统一处理
  } finally {
    pwd.loading = false
  }
}
</script>

<style scoped>
.layout { height: 100%; }

/* ---------- 侧边栏 ---------- */
.aside {
  background: #1d2129;
  display: flex;
  flex-direction: column;
}
.logo {
  display: flex; align-items: center; gap: 8px;
  height: 56px; padding: 0 20px; flex: none;
  color: #fff; font-size: 16px; font-weight: 700; letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, .08);
}
.logo-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: #2f54eb; flex: none;
}
.menu {
  border-right: none; background: transparent;
  padding: 12px 8px;
  flex: 1;
}
/* 菜单项：原型 .menu a（gap 10 / padding 10px 14px / radius 6 / 主色高亮） */
.menu :deep(.el-menu-item) {
  height: 40px; line-height: 40px;
  padding: 0 14px !important;
  margin-bottom: 4px;
  border-radius: 6px;
  color: #c9cdd4;
  font-size: 14px;
}
.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, .06);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: #2f54eb;
  color: #fff;
}
.ico {
  width: 16px; text-align: center; font-size: 14px;
  margin-right: 10px; display: inline-block;
}
.foot {
  margin-top: auto; padding: 16px 20px;
  font-size: 12px; color: #5f6672;
}

/* ---------- 顶部栏 ---------- */
.header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e6e8eb;
  display: flex; align-items: center; gap: 8px;
  padding: 0 24px;
  position: sticky; top: 0; z-index: 10;
}
.account { margin-left: auto; }
.account-name { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #1d2129; cursor: pointer; outline: none; }
.avatar {
  width: 22px; height: 22px; border-radius: 50%; background: #2f54eb; color: #fff;
  font-size: 12px; display: inline-flex; align-items: center; justify-content: center; flex: none;
}
.crumb { color: #86909c; }
.title { font-size: 16px; font-weight: 600; color: #1f2329; }

/* ---------- 内容区（原型 .page{padding:24px}） ---------- */
.main { padding: 24px; }
</style>
