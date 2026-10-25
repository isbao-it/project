/**
 * custom-tab-bar/index.js
 * 自定义 TabBar 组件
 * 固定首页、发布、我的，角色不影响入口顺序。
 */

const app = getApp()

Component({
  data: {
    selected: 0,
    list: [
      { pagePath: '/pages/workGallery/workGallery', text: '首页', key: 'home' },
      { pagePath: '/pages/workList/workList', text: '发布', key: 'publish' },
      { pagePath: '/pages/mine/mine', text: '我的', key: 'mine' }
    ]
  },

  lifetimes: {
    /** 挂载时按实际落点高亮，避免组件晚于页面 onShow 创建。 */
    attached() {
      this.setSelected(app.getCurrentPageUrl().split('?')[0])
    }
  },

  methods: {
    /**
     * 各页面 onShow 时调用，同步当前选中的 tab
     * @param {string} pagePath - 当前页面路径（如 '/pages/mine/mine'）
     */
    setSelected(pagePath) {
      const idx = this.data.list.findIndex(t => t.pagePath === pagePath)
      if (idx >= 0) {
        this.setData({ selected: idx })
      }
    },

    /**
     * 点击 tab 切换页面
     * @param {Object} e - 点击事件，通过 data-index 获取目标 tab
     */
    switchTab(e) {
      const tab = this.data.list[e.currentTarget.dataset.index]
      if (!tab) return
      if (tab.pagePath !== '/pages/workGallery/workGallery' && !app.requireLogin(tab.pagePath)) return
      wx.switchTab({ url: tab.pagePath })
    }
  }
})
