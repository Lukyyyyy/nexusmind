declare module 'vue-markdown-shiki' {
  import type { App, Component, Plugin } from 'vue';

  const markdownPlugin: Plugin;

  export const VueMarkdownIt: Component;
  export const VueMarkdownItProvider: Component;
  export function install(app: App): void;

  export default markdownPlugin;
}

declare module 'vue-markdown-shiki/style';
