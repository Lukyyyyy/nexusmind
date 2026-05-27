<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { useThemeStore } from '@/store/modules/theme';
import { useRouterPush } from '@/hooks/common/router';
import PwdLogin from './modules/pwd-login.vue';
import Register from './modules/register.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const themeStore = useThemeStore();
const { toggleLoginModule } = useRouterPush();

const moduleMap: Record<'pwd-login' | 'register', { title: string; component: Component }> = {
  'pwd-login': { title: '登录', component: PwdLogin },
  register: { title: '注册', component: Register }
};

const activeModule = computed(() => {
  return props.module === 'register' ? moduleMap.register : moduleMap['pwd-login'];
});

const bgColor = computed(() => {
  return themeStore.darkMode ? '#111827' : '#f0f2f5';
});
</script>

<template>
  <div class="login-page" :style="{ backgroundColor: bgColor }">
    <div class="login-shell">
      <section class="brand-panel">
        <div class="dot-grid"></div>
        <header class="brand-header">
          <div class="brand-mark">
            <SystemLogo class="text-28px" />
          </div>
          <strong>知枢 NexusMind</strong>
        </header>

        <div class="brand-copy">
          <h1>企业级 AI 知识库管理平台</h1>
          <p class="role">NexusMind Team</p>
          <p>
            用统一的知识组织、检索增强与智能问答能力，让团队资料沉淀为可复用的业务知识，并在安全可控的环境中快速获得答案。
          </p>
        </div>

        <footer class="brand-footer">
          <span>NexusMind 企业版</span>
          <span>AI Knowledge Base</span>
        </footer>
      </section>

      <section class="form-panel">
        <main class="form-content">
          <div class="form-heading">
            <h2>{{ activeModule.title }}</h2>
            <span v-if="props.module === 'register'" class="account-tip">
              已有账号？
              <button type="button" class="account-link" @click="toggleLoginModule('pwd-login')">点此登录</button>
            </span>
            <span v-else class="account-tip">
              没有账号？
              <button type="button" class="account-link" @click="toggleLoginModule('register')">点此注册</button>
            </span>
          </div>

          <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
            <component :is="activeModule.component" />
          </Transition>
        </main>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  position: relative;
  min-height: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background-image: none !important;
  color: #1f2937;
}

.login-shell {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: minmax(320px, 480px) minmax(320px, 480px);
  width: min(960px, 100%);
  min-height: 560px;
  background: #fff;
  box-shadow: 0 26px 80px rgb(15 23 42 / 16%);
}

.brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 74px 64px 48px;
  background:
    radial-gradient(circle at 15% 18%, rgb(255 255 255 / 30%) 0 3px, transparent 4px),
    linear-gradient(145deg, #334155, #51647d);
  color: #fff;
}

.dot-grid {
  position: absolute;
  inset: 0;
  opacity: 0.34;
  background-image: radial-gradient(rgb(255 255 255 / 48%) 1px, transparent 1px);
  background-size: 12px 12px;
  mask-image: radial-gradient(circle at 65% 58%, #000 0 34%, transparent 68%);
}

.brand-header,
.brand-copy,
.brand-footer {
  position: relative;
  z-index: 1;
}

.brand-header {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 34px;
  line-height: 1;
}

.brand-mark {
  display: flex;
  width: 42px;
  height: 42px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #ff6a00;
  color: #fff;
}

.brand-copy h1 {
  margin: 0 0 36px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: 0;
}

.brand-copy .role {
  margin: 0 0 18px;
  color: rgb(255 255 255 / 64%);
  font-size: 14px;
}

.brand-copy p:last-child {
  margin: 0;
  max-width: 330px;
  color: rgb(255 255 255 / 90%);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.9;
}

.brand-footer {
  display: flex;
  gap: 16px;
  border-top: 1px solid rgb(255 255 255 / 22%);
  padding-top: 30px;
  color: rgb(255 255 255 / 84%);
  font-size: 13px;
  font-weight: 600;
}

.form-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 56px 68px 48px;
  background: #fff;
}

.form-content {
  width: 100%;
  max-width: 348px;
  margin: auto;
}

.form-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  margin-bottom: 24px;
}

.form-heading h2 {
  margin: 0;
  color: #1f2937;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0;
}

.account-tip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: #64748b;
  font-size: 13px;
  white-space: nowrap;
}

.account-link {
  appearance: none;
  border: 0;
  padding: 0;
  background: transparent;
  color: #0f6b95;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
}

.account-link:hover {
  color: #0a5c82;
  text-decoration: underline;
}

:deep(.n-input) {
  --n-border-radius: 2px !important;
}

:deep(.n-button) {
  --n-border-radius: 2px !important;
}

:deep(.n-button--primary-type) {
  --n-color: #ff6a00 !important;
  --n-color-hover: #ff7a1a !important;
  --n-color-pressed: #e95f00 !important;
  --n-color-focus: #ff6a00 !important;
  --n-border: 1px solid #ff6a00 !important;
  --n-border-hover: 1px solid #ff7a1a !important;
  --n-border-pressed: 1px solid #e95f00 !important;
  --n-border-focus: 1px solid #ff6a00 !important;
}

@media (max-width: 900px) {
  .login-page {
    align-items: flex-start;
    padding: 28px 18px;
  }

  .login-shell {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .brand-panel {
    min-height: 300px;
    padding: 40px 36px 34px;
  }

  .brand-copy h1 {
    margin-bottom: 22px;
    font-size: 22px;
  }

  .brand-footer {
    padding-top: 22px;
  }

  .form-panel {
    min-height: 390px;
    padding: 48px 30px 36px;
  }

  .form-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }
}

@media (max-width: 520px) {
  .login-page {
    padding: 0;
  }

  .login-shell {
    width: 100%;
    box-shadow: none;
  }

  .brand-panel {
    min-height: 250px;
    padding: 34px 24px 28px;
  }

  .brand-header {
    font-size: 28px;
  }

  .brand-copy p:last-child {
    max-width: none;
    font-size: 13px;
  }

  .brand-footer {
    flex-direction: column;
    gap: 6px;
  }

  .form-panel {
    padding-inline: 24px;
  }

}
</style>
