/** 关注流：仅登录用户可分页浏览关注作者的展示作品。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    statusBarH: 0,
    list: [], page: 1, size: 10, total: 0,
    loading: false, error: false, noMore: false, loggedIn: false,
    refreshing: false
  },

  /** 读取状态栏高度，已登录时启动首屏请求。@returns {void} 初始化页面。 */
  onLoad() {
    const loggedIn = !!app.globalData.uid && app.checkLogin()
    this.setData({ statusBarH: wx.getWindowInfo().statusBarHeight, loggedIn })
    if (loggedIn) this.loadFeed(true)
  },

  /** 同步登录态，仅从未登录恢复时重拉。@returns {void} 未登录则引导登录。 */
  onShow() {
    const loggedIn = !!app.globalData.uid && app.checkLogin()
    const wasLoggedIn = this.data.loggedIn
    if (!loggedIn) {
      // 退出后清空旧用户作品，并使在途响应失效，避免旧列表回填。
      this._requestId = (this._requestId || 0) + 1
      this.setData({ loggedIn: false, list: [], page: 1, total: 0, loading: false, error: false, noMore: false })
      app.requireLogin('/pages/userList/userList')
      return
    }
    this.setData({ loggedIn: true })
    if (!wasLoggedIn) this.loadFeed(true)
  },

  /** 返回上一页；无返回栈时切回首页。@returns {void} 发起页面跳转。 */
  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/workGallery/workGallery' })
  },

  /** 下拉重读第一页，仅最新刷新结束时收起刷新提示。@returns {Promise<void>} 刷新任务。 */
  onPullDownRefresh() {
    const refreshId = this._refreshId = (this._refreshId || 0) + 1
    this.setData({ refreshing: true })
    return this.loadFeed(true).finally(() => {
      if (refreshId === this._refreshId) {
        this.setData({ refreshing: false })
        wx.stopPullDownRefresh()
      }
    })
  },

  /** 滚动触底时读取下一页，错误页留待手动重试。@returns {void} 触发分页读取。 */
  onReachBottom() {
    if (this.data.loggedIn && !this.data.noMore && !this.data.loading && !this.data.error) this.loadFeed()
  },

  /** 销毁页面时作废请求及刷新回调。@returns {void} 停止原生刷新提示。 */
  onUnload() {
    this._requestId = (this._requestId || 0) + 1
    this._refreshId = (this._refreshId || 0) + 1
    wx.stopPullDownRefresh()
  },

  /**
   * 读取关注作品，成功后才推进页码，失败保留已成功的页。
   * @param {boolean} refresh - 是否清空列表并重新读取第一页。
   * @returns {Promise<void>} 完成后更新列表、总数和加载状态。
   */
  loadFeed(refresh = false) {
    if (!app.globalData.uid || !app.checkLogin()) return Promise.resolve()
    if (!refresh && (this.data.loading || this.data.noMore)) return Promise.resolve()
    const requestId = this._requestId = (this._requestId || 0) + 1
    const page = refresh || !this.data.list.length ? 1 : this.data.page + 1
    if (refresh) this.setData({ list: [], page: 1, total: 0, noMore: false })
    this.setData({ loading: true, error: false })
    // 输入为信封中的 Work[]/total；出口仅补齐图片地址，保留字符串 pid 和 likep。
    return request('/pwork/following', 'GET', { page, size: this.data.size })
      .then(res => {
        if (requestId !== this._requestId) return
        const works = res.data.list.map(item => ({
          ...item, imageUrl: this.getFullUrl(item.vurl), avatarUrl: this.getFullUrl(item.avatar)
        }))
        const list = refresh ? [] : [...this.data.list]
        // 发布新作品会移动分页边界，按 pid 去重；页码仍按服务端分页推进。
        const seen = new Set(list.map(item => item.pid))
        works.forEach(item => {
          if (!seen.has(item.pid)) {
            list.push(item)
            seen.add(item.pid)
          }
        })
        this.setData({ list, page, total: res.data.total,
          noMore: page * this.data.size >= res.data.total || works.length === 0 })
      })
      .catch(() => {
        if (requestId !== this._requestId) return
        if (!app.globalData.uid || !app.checkLogin()) {
          // 请求层已处理 401 跳登录，此处撤下旧作品并显示登录引导。
          this.setData({ loggedIn: false, list: [], page: 1, total: 0, noMore: false, error: false })
        } else {
          this.setData({ error: true })
        }
      })
      .finally(() => {
        if (requestId === this._requestId) this.setData({ loading: false })
      })
  },

  /** 重试未成功的页，首屏从第一页读取。@returns {void} 发起重试。 */
  retryFeed() {
    if (!this.data.loading) this.loadFeed(this.data.list.length === 0)
  },

  /**
   * 将卡片的原始字符串 pid 交给详情页。
   * @param {{currentTarget: {dataset: {pid: string}}}} event - 作品卡片点击事件。
   * @returns {void} 合法 ID 才跳转。
   */
  goDetail(event) {
    const pid = event.currentTarget.dataset.pid
    if (typeof pid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(pid)) return
    wx.navigateTo({ url: '/pages/workDetail/workDetail?pid=' + encodeURIComponent(pid) })
  },

  /**
   * 沿用详情页规则补齐图片地址。
   * @param {string|null|undefined} path - 服务端相对路径或绝对地址。
   * @returns {string} 完整地址；缺图返回空字符串供视图占位。
   */
  getFullUrl(path) {
    if (!path) return ''
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 空态入口切换到现有首页。@returns {void} 发起 tab 跳转。 */
  goHome() {
    wx.switchTab({ url: '/pages/workGallery/workGallery' })
  },

  /** 登录入口由已有守卫保存回跳目标。@returns {void} 引导登录。 */
  goLogin() {
    app.requireLogin('/pages/userList/userList')
  }
})
