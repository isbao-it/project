/** 首页画廊：四个分栏独立分页，公开排序允许游客浏览，关注流需要登录。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    statusBarH: 0, navRight: 0,
    size: 10, loggedIn: false, followingLoginRequired: true,
    tabs: [
      { value: 'recommend', label: '推荐' },
      { value: 'latest', label: '最新' },
      { value: 'hot', label: '热门' },
      { value: 'following', label: '关注' }
    ],
    tabIndex: 0, tab: 'recommend',
    states: {
      recommend: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false },
      latest: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false },
      hot: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false },
      following: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false }
    },
    category: '', categoryIndex: 0,
    categories: [{ pcid: '', pcname: '全部分类', count: 0 }],
    categoryLoading: false, categoryError: false
  },

  /** 首次进入只读取公开作品，不触发登录或管理请求。@returns {void} 初始化尺寸及请求序号。 */
  onLoad() {
    this._reqId = { recommend: 0, latest: 0, hot: 0, following: 0 }
    // 登录守卫会 reLaunch 销毁首页；只消费一次待恢复分栏，登录回来才能继续看关注。
    const tabIndex = this.data.tabs.findIndex(item => item.value === app.globalData.galleryLoginTab)
    delete app.globalData.galleryLoginTab
    if (tabIndex >= 0) this.setData({ tab: this.data.tabs[tabIndex].value, tabIndex })
    this._followingAuth = app.getAuthHeader().Authorization || ''
    this._followingUid = app.checkLogin() && this._followingAuth ? app.globalData.uid : ''
    const info = wx.getWindowInfo()
    const cap = wx.getMenuButtonBoundingClientRect()
    this.setData({ statusBarH: info.statusBarHeight, navRight: info.windowWidth - cap.left + 8 })
    this.loadCategories()
    this.loadGallery(this.data.tab, true)
  },

  /** 登录返回或换账号后清除旧关注缓存，当前处于关注时重新请求。@returns {void} 同步登录态及首页高亮。 */
  onShow() {
    if (this.getTabBar()) this.getTabBar().setSelected('/pages/workGallery/workGallery')
    const auth = app.getAuthHeader().Authorization || ''
    const uid = app.checkLogin() && auth ? app.globalData.uid : ''
    this.setData({ loggedIn: !!uid, followingLoginRequired: !uid })
    if (uid !== this._followingUid || auth !== this._followingAuth) {
      this._followingUid = uid
      this._followingAuth = auth
      this._reqId.following += 1
      this.setData({ 'states.following': { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false } })
      if (uid && this.data.tab === 'following') this.loadGallery('following', true)
    }
  },

  /** 离页后作废各分栏及分类请求，避免更新已销毁页面。@returns {void} 标记页面失效。 */
  onUnload() {
    this._unloaded = true
    for (const tab of this.data.tabs) this._reqId[tab.value] += 1
    this._categoryRequestId = (this._categoryRequestId || 0) + 1
  },

  /** 从 data.list 读取分类；失败时保留现有选项，供用户重试。 */
  loadCategories() {
    if (this.data.categoryLoading) return this._categoryPromise
    const requestId = this._categoryRequestId = (this._categoryRequestId || 0) + 1
    this.setData({ categoryLoading: true, categoryError: false })
    this._categoryPromise = request('/category/list', 'GET')
      .then(res => {
        if (requestId !== this._categoryRequestId) return
        const categories = [{ pcid: '', pcname: '全部分类', count: 0 }, ...res.data.list]
        const categoryIndex = categories.findIndex(item => item.pcid === this.data.category)
        // 刷新时分类可能已删除，回到全部分类并重新读取第一页。
        if (categoryIndex < 0) {
          this.setData({ categories, categoryIndex: 0, category: '' })
          return this.loadGallery(this.data.tab, true)
        }
        this.setData({ categories, categoryIndex })
      })
      .catch(() => {
        if (requestId === this._categoryRequestId) this.setData({ categoryError: true })
      })
      .finally(() => {
        if (requestId === this._categoryRequestId) this.setData({ categoryLoading: false })
      })
    return this._categoryPromise
  },

  /** 点击分栏时同步 swiper，只懒加载未成功读取的列表。@param {Object} event 含 data-tab 的点击事件。@returns {Promise<void>|void} 首屏请求。 */
  onTabChange(event) {
    const tab = event.currentTarget.dataset.tab
    const tabIndex = this.data.tabs.findIndex(item => item.value === tab)
    if (tabIndex < 0) return
    this.setData({ tab, tabIndex })
    const state = this.data.states[tab]
    if (state.page === 0 && !state.loading) return this.loadGallery(tab)
  },

  /** 滑动时同步排序高亮，保留其它分栏数据与滚动位置。@param {Object} event swiper 的 current 事件。@returns {Promise<void>|void} 首屏请求。 */
  onSwiperChange(event) {
    const tabIndex = event.detail.current
    const selected = this.data.tabs[tabIndex]
    if (!selected) return
    this.setData({ tab: selected.value, tabIndex })
    const state = this.data.states[selected.value]
    if (state.page === 0 && !state.loading) return this.loadGallery(selected.value)
  },

  /** 只刷新发起下拉的分栏，完成或失败后结束该分栏动画。@param {Object} event 含 data-tab 的刷新事件。@returns {Promise<void>|void} 刷新请求。 */
  onRefresh(event) {
    const tab = event.currentTarget.dataset.tab
    if (!this.data.states[tab] || this._unloaded) return
    this.setData({ ['states.' + tab + '.refreshing']: true })
    return this.loadGallery(tab, true)
  },

  /** 只为触底分栏追加下一页，失败页留给重试按钮。@param {Object} event 含 data-tab 的触底事件。@returns {Promise<void>|void} 下一页请求。 */
  onLoadMore(event) {
    const tab = event.currentTarget.dataset.tab
    const state = this.data.states[tab]
    if (state && !state.noMore && !state.loading && !state.error) return this.loadGallery(tab)
  },

  /** 点击分类 chip 时按 pcid 找回位置，分类 pcid 始终按字符串传给接口。 */
  onCategoryChange(event) {
    const categoryIndex = this.data.categories.findIndex(item => item.pcid === event.currentTarget.dataset.pcid)
    const selected = this.data.categories[categoryIndex]
    if (!selected || selected.pcid === this.data.category) return
    this.setData({ categoryIndex, category: selected.pcid })
    this.loadGallery(this.data.tab, true)
  },

  /**
   * 按分栏读取分页，只有成功响应才推进页码；刷新只替代本分栏旧请求。
   * @param {string} tab 分栏名称。
   * @param {boolean} refresh 是否清空该分栏并读取第一页。
   * @returns {Promise<void>} 更新该分栏的列表和加载状态。
   */
  loadGallery(tab = this.data.tab, refresh = false) {
    const state = this.data.states[tab]
    if (!state || this._unloaded) return Promise.resolve()
    const key = 'states.' + tab
    const following = tab === 'following'
    const auth = app.getAuthHeader().Authorization
    if (following && (!app.globalData.uid || !app.checkLogin() || !auth)) {
      this._reqId[tab] += 1
      this.setData({ loggedIn: false, followingLoginRequired: true,
        [key]: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false } })
      return Promise.resolve()
    }
    if (!refresh && (state.loading || state.noMore)) return Promise.resolve()
    // 切分栏不取消其它分栏；只有本分栏刷新或离页才使自己的旧响应失效。
    const requestId = ++this._reqId[tab]
    const page = refresh ? 1 : state.page + 1
    if (refresh) this.setData({ [key + '.list']: [], [key + '.page']: 0, [key + '.total']: 0, [key + '.noMore']: false })
    this.setData({ [key + '.loading']: true, [key + '.error']: false })
    if (following) this.setData({ followingLoginRequired: false })
    const query = { page, size: this.data.size }
    if (!following) {
      query.tab = tab
      if (this.data.category) query.category = this.data.category
    }
    // 共享 request 的 401 会自动跳登录；关注流需就地展示登录引导，因此在此保留认证并单独处理响应。
    const task = following ? new Promise((resolve, reject) => {
      wx.request({
        url: app.globalData.baseUrl + '/pwork/following', method: 'GET', data: query,
        header: { 'content-type': 'application/json', Authorization: auth },
        success: res => {
          if (res.statusCode === 401) reject({ statusCode: 401 })
          else if (res.statusCode >= 200 && res.statusCode < 300 && res.data && res.data.code === 'ok') resolve(res.data)
          else reject(res.data || {})
        },
        fail: reject
      })
    }) : request('/pwork/list', 'GET', query)
    return task
      .then(res => {
        if (requestId !== this._reqId[tab] || (following && auth !== app.getAuthHeader().Authorization)) return
        // 接口入口为 Work[]/total；出口补齐展示地址，字符串 pid 保持原样。
        const works = res.data.list.map(item => ({
          ...item,
          imageUrl: this.getFullUrl(item.vurl),
          // 头像与作品图同为相对路径，统一走 getFullUrl，缺头像时 avatarUrl 留空由模板走占位。
          avatarUrl: item.avatar ? this.getFullUrl(item.avatar) : ''
        }))
        const list = refresh ? [] : [...this.data.states[tab].list]
        // 翻页期间作品排序可能变化，以原始字符串 pid 去重；页码仍按服务端分页推进。
        const seen = new Set(list.map(item => item.pid))
        works.forEach(item => {
          if (!seen.has(item.pid)) {
            list.push(item)
            seen.add(item.pid)
          }
        })
        this.setData({ [key + '.list']: list, [key + '.page']: page, [key + '.total']: res.data.total,
          [key + '.noMore']: page * this.data.size >= res.data.total || works.length === 0 })
      })
      .catch(error => {
        if (requestId !== this._reqId[tab] || (following && auth !== app.getAuthHeader().Authorization)) return
        if (following && error && error.statusCode === 401) {
          app.clearLoginInfo(true)
          this.setData({ loggedIn: false, followingLoginRequired: true,
            [key]: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false, refreshing: false } })
        } else this.setData({ [key + '.error']: true })
      })
      .finally(() => {
        if (requestId === this._reqId[tab]) this.setData({ [key + '.loading']: false, [key + '.refreshing']: false })
      })
  },

  /** 重试指定分栏失败页，不跳过作品。@param {Object} event 含 data-tab 的重试事件。@returns {Promise<void>|void} 重试请求。 */
  retryGallery(event) {
    const tab = event.currentTarget.dataset.tab
    const state = this.data.states[tab]
    if (state && !state.loading) return this.loadGallery(tab, state.page === 0)
  },

  /** 将卡片的字符串 pid 原样交给详情路由，不转为数值。 */
  goDetail(event) {
    const pid = event.currentTarget.dataset.pid
    if (typeof pid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(pid)) return
    wx.navigateTo({ url: `/pages/workDetail/workDetail?pid=${encodeURIComponent(pid)}` })
  },

  /** 图片兼容绝对 URL、相对路径及缺省图。 */
  getFullUrl(path) {
    if (!path) return '/images/default.jpg'
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 分类入口：跳独立分类页，不改变当前排序、不触发列表请求。 */
  goCategory() {
    wx.navigateTo({ url: '/pages/category/category' })
  },

  /** 首页登录入口，暂存分栏供登录后新建首页恢复。@returns {void} 由现有登录守卫发起跳转。 */
  goLogin() {
    app.globalData.galleryLoginTab = this.data.tab
    app.requireLogin('/pages/workGallery/workGallery')
  }
})
