<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand">
        <span class="dot"></span>
        <div>
          <div class="name">API 中心</div>
          <div class="sub">管理控制台</div>
        </div>
      </div>

      <el-radio-group v-model="mode" class="mode">
        <el-radio-button value="login">登录</el-radio-button>
        <el-radio-button value="register">注册</el-radio-button>
      </el-radio-group>

      <div v-if="authDisabled" class="notice">
        平台未启用登录校验（<span class="mono">auth.enabled=false</span>），可直接进入管理面。
      </div>
      <div v-else-if="mode === 'register'" class="notice">
        注册得到的账号为 <b>只读角色（VIEWER）</b>：可查看管理面但不能修改配置。如需管理权限，请联系拥有者在「账号管理」中调整。
      </div>
      <div v-else-if="firstRun" class="notice">
        首次使用：还没有任何账号，请先创建管理员账号（用户名 3-32 位小写字母/数字/_.-，密码 8-64 位含字母与数字）。
      </div>

      <!-- 表单做「对」是为了让**浏览器密码管理器**接管"记住密码"：el-form 渲染真实 <form>，
           输入框带 name + autocomplete，提交走原生 submit 事件 ⇒ 浏览器才会提示"保存密码/自动填充" -->
      <el-form label-position="top" class="form" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" name="username" placeholder="如 admin" autocomplete="username"
                    @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" name="password" type="password" show-password
                    placeholder="8-64 位，含字母与数字" autocomplete="current-password"
                    @keyup.enter="submit" />
        </el-form-item>
        <template v-if="mode === 'register'">
          <el-form-item label="确认密码">
            <el-input v-model="form.confirm" name="confirm" type="password" show-password placeholder="再输一次"
                      autocomplete="new-password" @keyup.enter="submit" />
          </el-form-item>
          <el-form-item label="显示名（可选）">
            <el-input v-model="form.displayName" name="displayName" placeholder="如 张三"
                      autocomplete="off" @keyup.enter="submit" />
          </el-form-item>
        </template>

        <!-- 「记住用户名」只在登录态显示：**只记用户名，不记口令**（口令交给浏览器密码管理器加密保管） -->
        <div v-if="mode === 'login'" class="remember-row">
          <el-checkbox v-model="remember" :disabled="authDisabled">记住用户名</el-checkbox>
          <span class="hint">密码请交给浏览器密码管理器（登录时弹出「保存密码」点保存即可）</span>
        </div>

        <el-button type="primary" class="submit" :loading="loading" native-type="submit">
          {{ mode === 'login' ? '登 录' : '注册并进入' }}
        </el-button>
      </el-form>

      <div class="foot">
        <template v-if="mode === 'login'">
          还没有账号？<a @click="switchMode('register')">注册</a>
        </template>
        <template v-else>
          已有账号？<a @click="switchMode('login')">去登录</a>
        </template>
        <span class="sep">·</span>
        <a @click="enterAnyway" v-if="authDisabled">直接进入</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { authStore, redirectTarget, registerIssue } from '@/utils/auth.mjs'
import {
  loadRememberedUsername, loadRememberFlag, saveRememberedUsername
} from '@/utils/loginPrefs.mjs'

// 登录 / 注册页（2026-09-18）：只做认证（无权限）；成功后写入令牌并跳到来源页。
const route = useRoute?.()
const router = useRouter?.()
const mode = ref('login')
const loading = ref(false)
const firstRun = ref(false)
const authDisabled = ref(false)
const form = reactive({ username: '', password: '', confirm: '', displayName: '' })
// 「记住用户名」（2026-09-24）：只持久化用户名；口令一律不落本地存储（见 utils/loginPrefs.mjs 注释）
const remember = ref(loadRememberFlag())

onMounted(async () => {
  // 回填上次记住的用户名（只读 storage，无网络）
  form.username = loadRememberedUsername()
  try {
    // 免鉴权端点：用于「首次初始化」引导与 auth.enabled=false 的放行
    const status = await http.get('/auth/status')
    firstRun.value = status && status.hasUser === false
    authDisabled.value = status && status.enabled === false
    authStore.setAuthEnabled(!authDisabled.value)
    if (firstRun.value) {
      mode.value = 'register'
    }
  } catch (e) {
    // 状态拉取失败不阻塞登录表单（按需登录处理，安全性优先）
  }
})

function switchMode(next) {
  mode.value = next
  form.password = ''
  form.confirm = ''
}

function enterAnyway() {
  router?.replace(redirectTarget(route?.query?.redirect))
}

async function submit() {
  if (loading.value) {
    return
  }
  const username = (form.username || '').trim().toLowerCase()
  if (!username) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (mode.value === 'register') {
    const issue = registerIssue({ username, password: form.password, confirm: form.confirm })
    if (issue) {
      ElMessage.warning(issue)
      return
    }
  } else if (!form.password) {
    ElMessage.warning('请输入密码')
    return
  }
  loading.value = true
  try {
    const path = mode.value === 'login' ? '/auth/login' : '/auth/register'
    const body = mode.value === 'login'
      ? { username, password: form.password }
      : { username, password: form.password, displayName: (form.displayName || '').trim() || null }
    const data = await http.post(path, body)
    authStore.setToken(data && data.token)
    authStore.setUser(data && data.user)
    if (mode.value === 'login') {
      saveRememberedUsername(undefined, username, remember.value)
    }
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功，已自动登录')
    router?.replace(redirectTarget(route?.query?.redirect))
  } catch (e) {
    // 错误提示已由 http 拦截器统一弹出（40105/40106/40001/40901 等）
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f6f8;
}
.login-card {
  width: 380px;
  padding: 32px 32px 24px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .04);
}
.brand { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.dot { width: 12px; height: 12px; border-radius: 50%; background: #2f54eb; flex: none; }
.name { font-size: 18px; font-weight: 600; color: #1d2129; line-height: 1.2; }
.sub { font-size: 12px; color: #6b7280; }
.mode { margin-bottom: 14px; }
.notice {
  font-size: 12px; line-height: 1.7; color: #8c6d1f; background: #fdf6ec;
  border: 1px solid #faecd8; border-radius: 4px; padding: 8px 10px; margin-bottom: 12px;
}
.form :deep(.el-form-item) { margin-bottom: 14px; }
.remember-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 2px 0 10px;
}
.remember-row .hint { font-size: 12px; color: #9ca3af; text-align: right; line-height: 1.4; }
.submit { width: 100%; }
.foot { margin-top: 14px; font-size: 12px; color: #6b7280; text-align: center; }
.foot a { color: #2f54eb; cursor: pointer; }
.sep { margin: 0 6px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
