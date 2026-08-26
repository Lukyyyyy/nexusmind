import 'vue-markdown-shiki/style';
import markdownPlugin from 'vue-markdown-shiki';
import type MarkdownIt from 'markdown-it';
import './plugins/assets';
import { setupAppVersionNotification, setupDayjs, setupIconifyOffline, setupLoading, setupNProgress } from './plugins';
import { setupStore } from './store';
import { setupRouter } from './router';
import { setupI18n } from './locales';
import App from './App.vue';
async function setupApp() {
  setupLoading();

  setupNProgress();

  setupIconifyOffline();

  setupDayjs();

  const app = createApp(App);

  setupStore(app);

  await setupRouter(app);

  setupI18n(app);

  setupAppVersionNotification();

  app.use(markdownPlugin, {
    config(md: MarkdownIt) {
      const renderText = md.renderer.rules.text;
      md.renderer.rules.text = (tokens, index, options, env, self) => {
        const text = renderText
          ? renderText(tokens, index, options, env, self)
          : md.utils.escapeHtml(tokens[index].content);
        return text.replace(
          /(?<=\p{Script=Han})\*\*([^*\n]+?)\*\*(?=\p{Script=Han})/gu,
          '<strong>$1</strong>'
        );
      };
    }
  });

  app.mount('#app');
}

setupApp();
