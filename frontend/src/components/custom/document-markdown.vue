<script setup lang="ts">
import MarkdownIt from 'markdown-it';
import math from '@traptitech/markdown-it-katex';
import DOMPurify from 'dompurify';
import axios from 'axios';
import 'katex/dist/katex.min.css';
import { getAuthorization } from '@/service/request/shared';
import { getServiceBaseURL } from '@/utils/service';

const props = defineProps<{ content: string; fileMd5: string }>();
const html = ref('');
const md = new MarkdownIt({ html: true, breaks: true }).use(math, {
  throwOnError: false,
  trust: false,
  strict: 'ignore',
  maxExpand: 1000,
  maxSize: 20
});
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
let controller: AbortController | undefined;
const urls = new Set<string>();

function cleanup() {
  controller?.abort();
  urls.forEach(url => URL.revokeObjectURL(url));
  urls.clear();
}

watch(() => [props.content, props.fileMd5], async () => {
  cleanup();
  const abort = new AbortController();
  controller = abort;
  const document = new DOMParser().parseFromString(DOMPurify.sanitize(md.render(props.content)), 'text/html');
  const images = Array.from(document.querySelectorAll('img'));
  // 移除原始 src，禁止未鉴权请求以及文档内任意外部图片请求。
  const sources = images.map(img => img.getAttribute('src') || '');
  images.forEach(img => {
    img.removeAttribute('src');
    img.removeAttribute('srcset');
    img.alt ||= '图片加载中';
  });
  html.value = document.body.innerHTML;
  // 按顺序加载，避免图片较多时瞬间占用过多内存或连接。
  for (const [index, img] of images.entries()) {
    const match = sources[index].match(/^\/api\/v1\/documents\/([a-fA-F0-9]{32})\/assets\/([a-f0-9]{64}\.(?:png|jpg|webp|gif))$/);
    if (!match || match[1] !== props.fileMd5) {
      img.alt = '图片未保存，请重新解析文档';
      continue;
    }
    try {
      const response = await axios.get<Blob>(`${baseURL}/documents/${match[1]}/assets/${match[2]}`, {
        headers: { Authorization: getAuthorization() }, responseType: 'blob', signal: abort.signal
      });
      if (abort.signal.aborted) return;
      if (!/^image\/(png|jpeg|webp|gif)$/.test(response.data.type)) throw new Error('Invalid image type');
      const url = URL.createObjectURL(response.data);
      urls.add(url);
      img.src = url;
      img.alt = '文档插图';
    } catch {
      if (abort.signal.aborted) return;
      img.alt = '图片加载失败或无访问权限';
    }
  }
  if (!abort.signal.aborted) html.value = document.body.innerHTML;
}, { immediate: true });

onBeforeUnmount(cleanup);
</script>

<template>
  <!-- 文档 HTML 及 KaTeX 输出经过 DOMPurify，图片仅来自鉴权后的 Blob。 -->
  <div class="document-markdown" v-html="html" />
</template>

<style scoped>
.document-markdown :deep(img) { max-width: 100%; height: auto; }
.document-markdown :deep(.katex-display) { overflow-x: auto; overflow-y: hidden; padding: 4px 0; }
.document-markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
.document-markdown :deep(td), .document-markdown :deep(th) { border: 1px solid #d9d9d9; padding: 6px 10px; }
.document-markdown :deep(.katex-error) { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
