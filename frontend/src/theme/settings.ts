/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#245bdb',
  otherColor: { info: '#287fd1', success: '#18a058', warning: '#e6a23c', error: '#d9485f' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 54, breadcrumb: { visible: true, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 38, mode: 'button' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 218,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: '知枢 NexusMind' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(255, 255, 255)',
        inverted: 'rgb(0, 20, 40)',
        'base-text': 'rgb(24, 34, 52)'
      },
      boxShadow: {
        header: 'none',
        sider: 'none',
        tab: 'none'
      }
    },
    dark: { colors: { container: 'rgb(28, 28, 28)', layout: 'rgb(18, 18, 18)', 'base-text': 'rgb(224, 224, 224)' } }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {
  themeScheme: 'light',
  themeColor: '#245bdb',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  header: { height: 54, breadcrumb: { visible: true, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 38, mode: 'button' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 218,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  tokens: themeSettings.tokens
};
