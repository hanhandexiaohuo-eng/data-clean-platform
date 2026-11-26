<template>
  <div class="login-page">
    <!-- 动画粒子背景 -->
    <div class="animated-bg">
      <div class="particle" v-for="n in 30" :key="n"
        :style="{
          left: Math.random() * 100 + '%',
          top: Math.random() * 100 + '%',
          width: (Math.random() * 80 + 20) + 'px',
          height: (Math.random() * 80 + 20) + 'px',
          animationDelay: (Math.random() * 15) + 's',
          animationDuration: (Math.random() * 20 + 10) + 's',
          opacity: Math.random() * 0.3 + 0.05,
        }">
      </div>
      <div class="wave"></div>
      <div class="wave wave-2"></div>
    </div>

    <!-- 登录卡片 -->
    <div class="login-container">
      <div class="login-card">
        <!-- 左侧品牌区 -->
        <div class="brand-panel">
          <div class="brand-content">
            <div class="brand-icon">
              <svg viewBox="0 0 80 80" width="80" height="80">
                <defs>
                  <linearGradient id="iconGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" style="stop-color:#667eea;stop-opacity:1" />
                    <stop offset="100%" style="stop-color:#764ba2;stop-opacity:1" />
                  </linearGradient>
                </defs>
                <rect x="8" y="20" width="12" height="40" rx="3" fill="url(#iconGrad)" />
                <rect x="24" y="10" width="12" height="50" rx="3" fill="url(#iconGrad)" opacity="0.85" />
                <rect x="40" y="28" width="12" height="32" rx="3" fill="url(#iconGrad)" opacity="0.7" />
                <rect x="56" y="16" width="12" height="44" rx="3" fill="url(#iconGrad)" opacity="0.9" />
              </svg>
            </div>
            <h1 class="brand-title">DataClean</h1>
            <p class="brand-subtitle">数据清洗与可视化分析平台</p>
            <p class="brand-desc">支持 CSV / Excel 文件导入、智能数据清洗、<br/>交互式图表分析与 AI 驱动的数据洞察</p>
          </div>
        </div>

        <!-- 右侧登录表单 -->
        <div class="form-panel">
          <div class="form-content">
            <h2 class="form-title">欢迎登录</h2>
            <p class="form-subtitle">登录后开始体验数据智能分析</p>

            <el-form
              ref="loginFormRef"
              :model="loginForm"
              :rules="loginRules"
              class="login-form"
              @keyup.enter="handleLogin"
            >
              <el-form-item prop="username">
                <el-input
                  v-model="loginForm.username"
                  placeholder="请输入用户名"
                  :prefix-icon="User"
                  size="large"
                />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="请输入密码"
                  :prefix-icon="Lock"
                  size="large"
                  show-password
                />
              </el-form-item>
              <el-form-item>
                <el-button
                  type="primary"
                  size="large"
                  class="login-btn"
                  :loading="loading"
                  @click="handleLogin"
                >
                  {{ loading ? '登录中...' : '登 录' }}
                </el-button>
              </el-form-item>
            </el-form>

            <div class="register-link">
              还没有账号？
              <el-link type="primary" @click="showRegister = true">立即注册</el-link>
            </div>
          </div>
        </div>
      </div>

      <div class="login-footer">
        &copy; 2026 DataClean Platform. All rights reserved.
      </div>
    </div>

    <!-- 注册对话框 -->
    <el-dialog v-model="showRegister" title="用户注册" width="440px" :close-on-click-modal="false" center>
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-width="0"
      >
        <el-form-item prop="username">
          <el-input v-model="registerForm.username" placeholder="用户名（3-20位字母数字）" size="large" />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input v-model="registerForm.nickname" placeholder="昵称（选填）" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="registerForm.password" type="password" placeholder="密码（至少6位）" size="large" show-password />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input v-model="registerForm.confirmPassword" type="password" placeholder="确认密码" size="large" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRegister = false">取消</el-button>
        <el-button type="primary" :loading="registerLoading" @click="handleRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login, register } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const loginFormRef = ref(null)
const loginForm = reactive({
  username: '',
  password: '',
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  try {
    await loginFormRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    const res = await login({
      username: loginForm.username,
      password: loginForm.password,
    })
    userStore.setLoginInfo(res)
    ElMessage.success('登录成功')
    router.push('/dataset')
  } catch (e) {
    // Error handled by request interceptor
  } finally {
    loading.value = false
  }
}

// --- Register ---
const showRegister = ref(false)
const registerLoading = ref(false)
const registerFormRef = ref(null)
const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
})

const validateConfirmPassword = (rule, value, callback) => {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度3-20位', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '仅支持字母、数字和下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  try {
    await registerFormRef.value.validate()
  } catch {
    return
  }

  registerLoading.value = true
  try {
    const res = await register({
      username: registerForm.username,
      password: registerForm.password,
      nickname: registerForm.nickname || registerForm.username,
    })
    userStore.setLoginInfo(res)
    ElMessage.success('注册成功，已自动登录')
    showRegister.value = false
    router.push('/dataset')
  } catch (e) {
    // Error handled by interceptor
  } finally {
    registerLoading.value = false
  }
}
</script>

<style scoped>
.login-page {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

/* ===== 动画背景 ===== */
.animated-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #0f0c29 0%, #1a1a3e 30%, #24243e 60%, #302b63 100%);
  z-index: 0;
}

.animated-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 20% 30%, rgba(102, 126, 234, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 80% 70%, rgba(118, 75, 162, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 50% 50%, rgba(102, 126, 234, 0.08) 0%, transparent 60%);
}

/* 浮动粒子 */
.particle {
  position: absolute;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.6), rgba(118, 75, 162, 0.4));
  animation: floatParticle 15s infinite ease-in-out;
}

@keyframes floatParticle {
  0%, 100% {
    transform: translateY(0) translateX(0) scale(1);
    opacity: 0.1;
  }
  25% {
    transform: translateY(-60px) translateX(30px) scale(1.3);
    opacity: 0.3;
  }
  50% {
    transform: translateY(-20px) translateX(-20px) scale(0.8);
    opacity: 0.15;
  }
  75% {
    transform: translateY(-80px) translateX(10px) scale(1.2);
    opacity: 0.25;
  }
}

/* 波浪效果 */
.wave {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 200%;
  height: 300px;
  background: linear-gradient(180deg, transparent 0%, rgba(102, 126, 234, 0.05) 50%, rgba(118, 75, 162, 0.08) 100%);
  border-radius: 40%;
  animation: waveMove 12s infinite linear;
  z-index: 1;
}

.wave-2 {
  bottom: -30px;
  animation: waveMove 18s infinite linear reverse;
  opacity: 0.5;
}

@keyframes waveMove {
  0% { transform: translateX(0) rotate(0deg); }
  100% { transform: translateX(-50%) rotate(360deg); }
}

/* ===== 登录卡片容器 ===== */
.login-container {
  position: relative;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.login-card {
  display: flex;
  background: rgba(255, 255, 255, 0.97);
  border-radius: 20px;
  box-shadow:
    0 20px 60px rgba(0, 0, 0, 0.3),
    0 0 120px rgba(102, 126, 234, 0.15);
  overflow: hidden;
  backdrop-filter: blur(10px);
  max-width: 900px;
  width: 90vw;
}

/* ===== 左侧品牌面板 ===== */
.brand-panel {
  flex: 1;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 60px 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  position: relative;
  overflow: hidden;
}

.brand-panel::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -50%;
  width: 100%;
  height: 100%;
  background: radial-gradient(circle, rgba(255,255,255,0.1) 0%, transparent 70%);
  border-radius: 50%;
}

.brand-content {
  position: relative;
  z-index: 1;
  text-align: center;
}

.brand-icon {
  margin-bottom: 24px;
}

.brand-title {
  font-size: 36px;
  font-weight: 800;
  margin-bottom: 8px;
  letter-spacing: 2px;
}

.brand-subtitle {
  font-size: 16px;
  opacity: 0.9;
  margin-bottom: 24px;
  font-weight: 300;
  letter-spacing: 1px;
}

.brand-desc {
  font-size: 13px;
  opacity: 0.7;
  line-height: 1.8;
  font-weight: 300;
}

/* ===== 右侧表单面板 ===== */
.form-panel {
  flex: 1;
  padding: 60px 50px;
  display: flex;
  align-items: center;
}

.form-content {
  width: 100%;
}

.form-title {
  font-size: 26px;
  font-weight: 700;
  color: #1a1a2e;
  margin-bottom: 8px;
}

.form-subtitle {
  font-size: 14px;
  color: #909399;
  margin-bottom: 36px;
}

.login-form {
  margin-bottom: 20px;
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.login-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  letter-spacing: 4px;
  border-radius: 10px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
  font-weight: 600;
}

.login-btn:hover {
  opacity: 0.9;
  background: linear-gradient(135deg, #667eea, #764ba2);
}

.register-link {
  text-align: center;
  font-size: 14px;
  color: #909399;
}

/* ===== 页脚 ===== */
.login-footer {
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  margin-top: 30px;
  text-align: center;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .login-card {
    flex-direction: column;
    max-width: 400px;
  }
  .brand-panel {
    padding: 30px 20px;
  }
  .brand-title {
    font-size: 24px;
  }
  .brand-desc {
    display: none;
  }
  .form-panel {
    padding: 30px 30px;
  }
}
</style>
