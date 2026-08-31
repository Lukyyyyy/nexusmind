<script setup lang="ts">
const loading = ref(false);
const saving = ref(false);
const model = reactive({ host: '', port: 465, username: '', password: '', fromAddress: '', sslEnabled: true, enabled: true, currentPassword: '' });
const cryptoConfigured = ref(false);
const provider = ref('smtp');
const configured = ref(false);
const sesRegion = ref('');
const testEmail = ref('');

async function load() {
  loading.value = true;
  const { data, error } = await request<Record<string, any>>({ url: '/admin/organization-management/smtp' });
  if (!error) {
    provider.value = data.provider || 'smtp';
    configured.value = Boolean(data.configured);
    sesRegion.value = data.region || '';
    Object.assign(model, { host: data.host || '', port: data.port || 465, username: data.username || '', fromAddress: data.fromAddress || '', sslEnabled: data.sslEnabled ?? true, enabled: data.enabled ?? true });
    cryptoConfigured.value = Boolean(data.cryptoConfigured);
  }
  loading.value = false;
}

async function save() {
  saving.value = true;
  const { error } = await request({ url: '/admin/organization-management/smtp', method: 'PUT', data: model });
  saving.value = false;
  if (!error) { window.$message?.success('邮件服务配置已保存'); model.password = ''; model.currentPassword = ''; await load(); }
}

async function setEnabled(value: boolean) {
  model.enabled = value;
  saving.value = true;
  const { error } = await request({ url: '/admin/organization-management/smtp/enabled', method: 'PUT', data: { enabled: value } });
  saving.value = false;
  if (error) model.enabled = !value;
  else window.$message?.success(value ? '邮件服务已启用' : '邮件服务已停用');
}

async function sendTest() {
  if (!testEmail.value) return;
  const { error } = await request({ url: '/admin/organization-management/smtp/test', method: 'POST', data: { email: testEmail.value } });
  if (!error) window.$message?.success('测试邮件已发送');
}
onMounted(load);
</script>

<template>
  <NSpin :show="loading">
    <div class="smtp-heading">
      <div>
        <h2>邮件服务配置</h2>
        <p>用于账号验证、组织通知及系统消息投递</p>
      </div>
      <NTag :type="configured && model.enabled ? 'success' : 'default'" :bordered="false" round>
        {{ configured && model.enabled ? '服务已启用' : '服务未启用' }}
      </NTag>
    </div>

    <NAlert v-if="provider === 'smtp' && !cryptoConfigured" type="warning" class="security-alert" :bordered="false">
      请先在部署环境设置 SMTP_CRYPTO_SECRET，才能安全保存 SMTP 授权码。
    </NAlert>

    <section v-if="provider === 'tencent-ses'" class="settings-panel ses-panel">
      <div class="panel-title">
        <div class="panel-icon"><SvgIcon icon="solar:cloud-linear" /></div>
        <div><strong>腾讯云 SES API</strong><span>个人实名认证账号通过 API 投递邮件</span></div>
      </div>
      <div class="ses-values">
        <div><span>地域</span><strong>{{ sesRegion || '未配置' }}</strong></div>
        <div><span>发件地址</span><strong>{{ model.fromAddress || '未配置' }}</strong></div>
        <div><span>配置状态</span><strong>{{ configured ? '配置完整' : '请检查部署环境变量和模板 ID' }}</strong></div>
      </div>
      <div class="enable-row ses-enable-row">
        <div><strong>启用邮件投递</strong><span>关闭后系统将暂停发送全部邮件</span></div>
        <NSwitch :value="model.enabled" :loading="saving" @update:value="setEnabled" />
      </div>
      <NAlert v-if="!configured" type="warning" :bordered="false">
        需要配置腾讯云 SES 密钥、发件地址及全部业务模板 ID，并重启后端。
      </NAlert>
    </section>

    <div v-else class="smtp-grid">
      <section class="settings-panel">
        <div class="panel-title">
          <div class="panel-icon"><SvgIcon icon="solar:server-square-linear" /></div>
          <div><strong>连接参数</strong><span>配置服务商提供的 SMTP 信息</span></div>
        </div>
        <NForm :model="model" label-placement="top">
          <div class="form-grid">
            <NFormItem label="SMTP 服务器" class="span-2"><NInput v-model:value="model.host" placeholder="smtp.example.com" /></NFormItem>
            <NFormItem label="端口"><NInputNumber v-model:value="model.port" :min="1" :max="65535" class="w-full" /></NFormItem>
            <NFormItem label="连接方式">
              <NRadioGroup v-model:value="model.sslEnabled" class="protocol-group">
                <NRadioButton :value="true">SSL</NRadioButton>
                <NRadioButton :value="false">STARTTLS</NRadioButton>
              </NRadioGroup>
            </NFormItem>
            <NFormItem label="SMTP 账号" class="span-2"><NInput v-model:value="model.username" placeholder="请输入服务账号" /></NFormItem>
            <NFormItem label="授权码" class="span-2"><NInput v-model:value="model.password" type="password" show-password-on="click" placeholder="留空则保持原授权码" /></NFormItem>
            <NFormItem label="发件地址" class="span-2"><NInput v-model:value="model.fromAddress" placeholder="name@example.com" /></NFormItem>
          </div>
        </NForm>
      </section>

      <aside class="settings-panel side-panel">
        <div class="panel-title">
          <div class="panel-icon"><SvgIcon icon="solar:shield-check-linear" /></div>
          <div><strong>启用与验证</strong><span>更改配置前需验证管理员身份</span></div>
        </div>
        <div class="enable-row">
          <div><strong>启用邮件投递</strong><span>关闭后系统将暂停发送全部邮件</span></div>
          <NSwitch v-model:value="model.enabled" />
        </div>
        <NForm :model="model" label-placement="top">
          <NFormItem label="当前管理员密码">
            <NInput v-model:value="model.currentPassword" type="password" show-password-on="click" placeholder="请输入当前登录密码" />
          </NFormItem>
        </NForm>
        <NButton type="primary" block :loading="saving" :disabled="!cryptoConfigured || !model.currentPassword" @click="save">
          保存配置
        </NButton>
      </aside>
    </div>

    <section class="test-panel">
      <div>
        <strong>发送测试邮件</strong>
        <span>保存配置后，发送邮件以确认服务连通性</span>
      </div>
      <NInputGroup class="test-input">
        <NInput v-model:value="testEmail" placeholder="收件邮箱" />
        <NButton type="primary" secondary :disabled="!testEmail || !configured || !model.enabled" @click="sendTest">发送测试</NButton>
      </NInputGroup>
    </section>
  </NSpin>
</template>

<style scoped lang="scss">
.smtp-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.smtp-heading h2 { margin: 0; color: #263247; font-size: 16px; font-weight: 600; }
.smtp-heading p { margin: 5px 0 0; color: #8a94a6; font-size: 12px; }
.security-alert { margin-bottom: 18px; border-radius: 8px; }
.ses-panel { margin-bottom: 18px; }
.ses-values { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.ses-values > div { display: flex; flex-direction: column; gap: 5px; padding: 14px; border: 1px solid #e8edf3; border-radius: 8px; }
.ses-values span { color: #8a94a6; font-size: 12px; }
.ses-values strong { overflow-wrap: anywhere; color: #344054; font-size: 13px; }
.ses-enable-row { margin-bottom: 16px; }
.smtp-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(280px, 0.8fr); gap: 18px; }
.settings-panel, .test-panel { border: 1px solid #e5eaf1; border-radius: 9px; background: #fff; }
.settings-panel { padding: 22px; }
.panel-title { display: flex; align-items: center; gap: 11px; margin-bottom: 22px; }
.panel-title > div:last-child { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.panel-title strong, .test-panel strong { color: #263247; font-size: 14px; font-weight: 600; }
.panel-title span, .test-panel span, .enable-row span { color: #8a94a6; font-size: 12px; }
.panel-icon { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 8px; background: #eef4ff; color: #245bdb; font-size: 17px; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 16px; }
.span-2 { grid-column: span 2; }
.protocol-group { display: flex; }
.protocol-group :deep(.n-radio-button) { flex: 1; text-align: center; }
.side-panel { align-self: start; background: #fbfcfe; }
.enable-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; margin-bottom: 18px; border: 1px solid #e8edf3; border-radius: 8px; background: #fff; }
.enable-row > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.enable-row strong { color: #344054; font-size: 13px; font-weight: 500; }
.test-panel { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 18px 22px; margin-top: 18px; background: #fbfcfe; }
.test-panel > div { display: flex; flex-direction: column; gap: 4px; }
.test-input { width: min(440px, 50%); }
@media (max-width: 900px) {
  .smtp-grid { grid-template-columns: 1fr; }
  .side-panel { align-self: stretch; }
}
@media (max-width: 640px) {
  .ses-values { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
  .test-panel { align-items: stretch; flex-direction: column; }
  .test-input { width: 100%; }
}
</style>
