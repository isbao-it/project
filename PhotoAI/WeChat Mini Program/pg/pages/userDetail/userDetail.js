/** 用户公开主页：独立读取资料、作品和可见的收藏/喜欢。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    uid: '', user: null, avatarUrl: '', isSelf: false, statusBarH: 0,
    profileLoading: false, profileError: false, followLoading: false,
    showFavorites: false, showLikes: false, tab: 'works', refreshing: false,
    lists: {
      works: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false },
      favorites: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false },
      likes: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false }
    },
    emptyTips: { works: 'TA 还没有发布作品', favorites: 'TA 还没有公开的收藏', likes: 'TA 还没有公开的喜欢' }
  },

  /** 接收主页 uid 并并行读取资料和作品；缺参不请求。@param {{uid?:string}} options 路由参数。@returns {void} 初始化页面。 */
  onLoad(options = {}) {
    this.setData({ statusBarH: wx.getWindowInfo().statusBarHeight })
    if (typeof options.uid !== 'string' || !options.uid) {
      this.setData({ profileError: true })
      wx.showToast({ title: '参数错误', icon: 'none' })
      return
    }
    this.setData({ uid: options.uid, isSelf: options.uid === app.globalData.uid })
    this.loadProfile()
    this.loadWorks(true)
  },

  /** 编辑或登录返回时刷新资料和当前身份。@returns {void} 首次显示复用 onLoad 的读取。 */
  onShow() {
    if (this._shown) this.loadProfile()
    this._shown = true
  },

  /** 离页后拒绝所有迟到响应。@returns {void} 作废资料及三个列表的请求。 */
  onUnload() {
    this._unloaded = true
    for (const key of ['profile', 'works', 'favorites', 'likes']) {
      const id = '_' + key + 'RequestId'
      this[id] = (this[id] || 0) + 1
    }
  },

  /** 读取公开资料；隐私关闭时清缓存并作废该列表在途响应。@returns {Promise<void>} 同步资料、统计及可见 tab。 */
  loadProfile() {
    if (!this.data.uid || this.data.followLoading || this._unloaded) return Promise.resolve()
    const requestId = this._profileRequestId = (this._profileRequestId || 0) + 1
    this.setData({ profileLoading: true, profileError: false })
    return request('/user/' + encodeURIComponent(this.data.uid), 'GET')
      .then(res => {
        if (requestId !== this._profileRequestId) return
        const user = res.data
        this.setData({ user, avatarUrl: this.getFullUrl(user.avatar),
          isSelf: this.data.uid === app.globalData.uid,
          showFavorites: user.showFavorites === true, showLikes: user.showLikes === true })
        for (const tab of ['favorites', 'likes']) {
          if (!this.canView(tab)) {
            const id = '_' + tab + 'RequestId'
            this[id] = (this[id] || 0) + 1
            this.setData({ ['lists.' + tab]: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false } })
          }
        }
        if (!this.canView(this.data.tab)) this.setData({ tab: 'works' })
      })
      .catch(() => {
        if (requestId === this._profileRequestId) this.setData({ profileError: true })
      })
      .finally(() => {
        if (requestId === this._profileRequestId) this.setData({ profileLoading: false })
      })
  },

  /** 判断 tab 是否可见，未知类型拒绝。@param {string} tab 分栏名。@returns {boolean} 是否允许显示和请求。 */
  canView(tab) {
    return tab === 'works' || (tab === 'favorites' && this.data.showFavorites) || (tab === 'likes' && this.data.showLikes)
  },

  /** 切换可见分栏，首次进入才读取首页。@param {{currentTarget:{dataset:{tab:string}}}} e 分栏事件。@returns {void} 保留其它列表分页。 */
  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!this.canView(tab) || tab === this.data.tab) return
    this.setData({ tab })
    if (this.data.lists[tab].page === 0) this.loadList(tab)
  },

  /** 读取作品列表。@param {boolean} refresh 是否刷新首页。@returns {Promise<void>} 完成作品读取。 */
  loadWorks(refresh = false) { return this.loadList('works', refresh) },

  /** 读取可见收藏。@param {boolean} refresh 是否刷新首页。@returns {Promise<void>} 完成收藏读取。 */
  loadFavorites(refresh = false) { return this.loadList('favorites', refresh) },

  /** 读取可见喜欢。@param {boolean} refresh 是否刷新首页。@returns {Promise<void>} 完成喜欢读取。 */
  loadLikes(refresh = false) { return this.loadList('likes', refresh) },

  /**
   * 按分栏独立分页；刷新可替代旧请求，失败保留成功页码以供重试。
   * @param {string} tab 作品、收藏或喜欢分栏。
   * @param {boolean} refresh 是否清空当前列表并重读首页。
   * @returns {Promise<void>} 更新当前分栏的列表和分页状态。
   */
  loadList(tab, refresh = false) {
    if (!this.data.uid || !this.canView(tab) || this._unloaded) return Promise.resolve()
    const state = this.data.lists[tab]
    if (!refresh && (state.loading || state.noMore)) return Promise.resolve()
    const uid = encodeURIComponent(this.data.uid)
    const paths = {
      works: '/user/' + uid + '/works',
      favorites: '/user/' + uid + '/favorites',
      likes: '/user/' + uid + '/likes'
    }
    // 每个分栏独享请求序号；切 tab 不作废其它列表，刷新才替代自己的旧页。
    const id = '_' + tab + 'RequestId'
    const requestId = this[id] = (this[id] || 0) + 1
    const key = 'lists.' + tab
    const page = refresh ? 1 : state.page + 1
    if (refresh) this.setData({ [key]: { list: [], page: 0, total: 0, loading: false, error: false, noMore: false } })
    this.setData({ [key + '.loading']: true, [key + '.error']: false })
    return request(paths[tab], 'GET', { page, size: 10 }) // F16§4.3：页码从 1 开始，每页 10 条。
      .then(res => {
        if (requestId !== this[id]) return
        // 入口为公开 Work[]/total，出口只增加展示用的完整图片地址。
        const incoming = res.data.list.map(item => ({ ...item, imageUrl: this.getFullUrl(item.vurl) }))
        const list = refresh ? incoming : [...this.data.lists[tab].list, ...incoming]
        this.setData({ [key + '.list']: list, [key + '.page']: page, [key + '.total']: res.data.total,
          [key + '.noMore']: list.length >= res.data.total || incoming.length === 0 })
      })
      .catch(error => {
        if (requestId !== this[id]) return
        if (tab !== 'works' && error && error.code === 'forbidden') {
          this.setData({ [tab === 'favorites' ? 'showFavorites' : 'showLikes']: false,
            [key]: { list: [], page: 0, total: 0, loading: false, error: false, noMore: true } })
          if (this.data.tab === tab) this.setData({ tab: 'works' })
          wx.showToast({ title: '不可见', icon: 'none' })
        } else {
          this.setData({ [key + '.error']: true })
        }
      })
      .finally(() => {
        if (requestId === this[id]) this.setData({ [key + '.loading']: false })
      })
  },

  /** 当前列表触底读取下一页。@returns {Promise<void>} 加载任务由列表状态防重。 */
  onReachBottom() { return this.loadList(this.data.tab) },

  /** 重试当前失败页，页码仅在成功后推进。@returns {Promise<void>} 当前列表读取任务。 */
  retryList() { return this.loadList(this.data.tab, this.data.lists[this.data.tab].page === 0) },

  /** 下拉并行刷新资料和当前列表。@returns {Promise<void>} 完成后停止滚动区刷新动画。 */
  onPullDownRefresh() {
    this.setData({ refreshing: true })
    return Promise.all([this.loadProfile(), this.loadList(this.data.tab, true)])
      .finally(() => {
        if (!this._unloaded) this.setData({ refreshing: false })
        wx.stopPullDownRefresh()
      })
  },

  /**
   * 本人进入编辑，其他人发起关注切换；只用服务端确认结果更新状态。
   * @returns {Promise<void>} 关注完成后解除按钮锁定。
   */
  async toggleFollow() {
    if (!this.data.user || this.data.followLoading) return
    if (this.data.uid === app.globalData.uid) {
      if (app.requireLogin('/pages/userEdit/userEdit')) wx.navigateTo({ url: '/pages/userEdit/userEdit' })
      return
    }
    if (!app.requireLogin('/pages/userDetail/userDetail?uid=' + encodeURIComponent(this.data.uid))) return
    // 旧资料可能在关注成功之后到达，先作废，防止覆盖已确认的关注状态。
    this._profileRequestId = (this._profileRequestId || 0) + 1
    this.setData({ followLoading: true, profileLoading: false })
    try {
      const res = await request('/follow/' + encodeURIComponent(this.data.uid), this.data.user.followed ? 'DELETE' : 'POST')
      if (this._unloaded) return
      const patch = { 'user.followed': res.data.following }
      if (res.data.followers != null) patch['user.followersCount'] = res.data.followers
      this.setData(patch)
    } catch (error) {
      if (!this._unloaded) wx.showToast({ title: '关注操作失败，请重试', icon: 'none' })
    } finally {
      if (!this._unloaded) this.setData({ followLoading: false })
    }
  },

  /** 可见时携带主页 uid 进入关系列表，关闭时只提示。@param {{currentTarget:{dataset:{type:string}}}} e 统计点击事件。@returns {void} 登录后跳转或显示隐私提示。 */
  openRelation(e) {
    const type = e.currentTarget.dataset.type
    if (!this.data.user || !['following', 'followers'].includes(type)) return
    // 对方关闭该列表时只提示，不跳转。
    if (type === 'following' && this.data.user.showFollowing === false) {
      wx.showToast({ title: 'TA 未公开关注列表', icon: 'none' })
      return
    }
    if (type === 'followers' && this.data.user.showFollowers === false) {
      wx.showToast({ title: 'TA 未公开粉丝列表', icon: 'none' })
      return
    }
    const url = '/pages/relation/relation?type=' + type + '&uid=' + encodeURIComponent(this.data.uid)
    if (app.requireLogin(url)) wx.navigateTo({ url })
  },

  /** 点击作品进入已注册详情页。@param {{currentTarget:{dataset:{pid:string}}}} e 卡片事件。@returns {void} 合法作品 ID 才跳转。 */
  openWork(e) {
    const pid = e.currentTarget.dataset.pid
    if (typeof pid === 'string' && /^[A-Za-z0-9_-]{1,36}$/.test(pid)) {
      wx.navigateTo({ url: '/pages/workDetail/workDetail?pid=' + encodeURIComponent(pid) })
    }
  },

  /** 补全图片路径，缺图使用 mine 的默认图。@param {string} path 图片地址。@returns {string} 完整 URL 或默认资源。 */
  getFullUrl(path) {
    if (!path) return '/images/default.jpg'
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 返回上级页面，无栈时回首页。@returns {void} 发起微信页面跳转。 */
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/workGallery/workGallery' })
  }
})
