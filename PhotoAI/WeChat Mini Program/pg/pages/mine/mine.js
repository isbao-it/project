/** 展示本人资料、作品、收藏、喜欢和关系统计。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    statusBarH: 0, tab: 'works',
    followingCount: '—', followersCount: '—', likedTotal: '—',
    favorites: [], favoritesPage: 0, favoritesSize: 10, favoritesTotal: 0,
    favoritesNoMore: false, favoritesLoading: false, favoritesError: false,
    likes: [], likesPage: 0, likesTotal: 0,
    likesNoMore: false, likesLoading: false, likesError: false,
    userInfo: {}, avatarUrl: '', loading: false, profileError: false,
    visFavorite: true, visLike: true, visFollowing: true, visFollowers: true,
    privacyLoading: false, privacyReady: false,
    list: [], page: 0, size: 10, total: 0,
    worksLoading: false, worksError: false, noMore: false,
    status: '', statuses: ['', '待审核', '展示', '隐藏', '已驳回']
  },

  /** 读取系统状态栏高度，供自绘渐变头部避让。 */
  onLoad() {
    this.setData({ statusBarH: wx.getWindowInfo().statusBarHeight })
  },

  /** 进入及编辑返回时刷新资料、作品第一页及独立统计。 */
  onShow() {
    if (this.getTabBar()) this.getTabBar().setSelected('/pages/mine/mine')
    if (!app.requireLogin('/pages/mine/mine')) return
    this.refresh()
    this.loadStats()
  },

  /** 离开后使旧资料请求失效，避免覆盖编辑页刚保存的全局缓存。 */
  onHide() {
    this._profileRequestId = (this._profileRequestId || 0) + 1
  },

  /** 内容区触底只加载当前列表，隐藏的列表保留自己的页码。 */
  onReachBottom() {
    if (this.data.tab === 'works') {
      if (!this.data.noMore && !this.data.worksLoading) this.loadWorks()
    } else if (this.data.tab === 'favorites') {
      if (!this.data.favoritesNoMore && !this.data.favoritesLoading) this.loadFavorites()
    } else if (this.data.tab === 'likes') {
      if (!this.data.likesNoMore && !this.data.likesLoading) this.loadLikes()
    }
  },

  /** 切换固定分栏；收藏和喜欢各自在尚未成功取得第一页时请求。 */
  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!['works', 'favorites', 'likes', 'settings'].includes(tab) || tab === this.data.tab) return
    this.setData({ tab })
    if (tab === 'favorites' && this.data.favoritesPage === 0 && !this.data.favoritesLoading) {
      this.loadFavorites()
    }
    if (tab === 'likes' && this.data.likesPage === 0 && !this.data.likesLoading) {
      this.loadLikes()
    }
  },

  /** 单独读取总数；失败显示未知，不影响任一列表的分页状态。 */
  loadStats() {
    if (!app.requireLogin('/pages/mine/mine')) return Promise.resolve()
    const requestId = this._statsRequestId = (this._statsRequestId || 0) + 1
    this.setData({ followingCount: '—', followersCount: '—', likedTotal: '—' })
    // 两个请求只消费 total，绝不把单条样本并入作品或收藏列表。
    return Promise.all([
      request('/follow/following', 'GET', { page: 1, size: 1 })
        .then(res => {
          if (requestId === this._statsRequestId) this.setData({ followingCount: res.data.total })
        })
        .catch(() => {
          if (requestId === this._statsRequestId) this.setData({ followingCount: '—' })
        }),
      request('/follow/followers', 'GET', { page: 1, size: 1 })
        .then(res => {
          if (requestId === this._statsRequestId) this.setData({ followersCount: res.data.total })
        })
        .catch(() => {
          if (requestId === this._statsRequestId) this.setData({ followersCount: '—' })
        }),
      request('/user/' + encodeURIComponent(app.globalData.uid), 'GET')
        .then(res => {
          if (requestId !== this._statsRequestId) return
          this.setData({ likedTotal: res.data.likedTotal == null ? '—' : res.data.likedTotal })
        })
        .catch(() => {
          if (requestId === this._statsRequestId) this.setData({ likedTotal: '—' })
        })
    ])
  },

  /** 按统计栏类型进入关系列表；未登录时由守卫保存回跳目标。 */
  openRelation(e) {
    const type = e.currentTarget.dataset.type
    if (!['likes', 'following', 'followers'].includes(type)) return
    const url = '/pages/relation/relation?type=' + type
    if (app.requireLogin(url)) wx.navigateTo({ url })
  },

  /** 读取本人收藏；refresh 回第一页，失败保留已成功页码以便重试。 */
  loadFavorites(refresh = false) {
    if (!app.requireLogin('/pages/mine/mine')) return Promise.resolve()
    if (this.data.favoritesLoading && !refresh) return Promise.resolve()
    // 收藏独享请求序号，切分栏或作品筛选时不会使彼此的响应失效。
    const requestId = this._favoritesRequestId = (this._favoritesRequestId || 0) + 1
    const page = refresh ? 1 : this.data.favoritesPage + 1
    const params = { page, size: this.data.favoritesSize }
    if (refresh) this.setData({ favorites: [], favoritesPage: 0, favoritesTotal: 0, favoritesNoMore: false })
    this.setData({ favoritesLoading: true, favoritesError: false })
    return request('/favorite/mine', 'GET', params)
      .then(res => {
        if (requestId !== this._favoritesRequestId) return
        const works = res.data.list.map(item => ({ ...item, imageUrl: this.getFullUrl(item.vurl) }))
        const favorites = refresh ? works : [...this.data.favorites, ...works]
        this.setData({ favorites, favoritesPage: page, favoritesTotal: res.data.total,
          favoritesNoMore: favorites.length >= res.data.total || works.length === 0 })
      })
      .catch(() => {
        if (requestId === this._favoritesRequestId) this.setData({ favoritesError: true })
      })
      .finally(() => {
        if (requestId === this._favoritesRequestId) this.setData({ favoritesLoading: false })
      })
  },

  /** 重试收藏当前失败页，首屏失败时从第一页重试。 */
  retryFavorites() {
    this.loadFavorites(this.data.favoritesPage === 0)
  },

  /**
   * 取消收藏：二次确认后删除记录，成功后本地移除并同步计数。
   * @param {{currentTarget: {dataset: {pid: string}}}} e - 取消收藏事件
   * @returns {Promise<void>} 完成后结束
   */
  async removeFavorite(e) {
    const pid = e.currentTarget.dataset.pid
    if (!pid || this._removingFavorite) return
    const confirmed = await new Promise(resolve => wx.showModal({
      title: '取消收藏', content: '确定不再收藏这件作品吗？',
      success: r => resolve(r.confirm), fail: () => resolve(false)
    }))
    if (!confirmed) return
    this._removingFavorite = true
    try {
      await request('/favorite/' + encodeURIComponent(pid), 'DELETE')
      const favorites = this.data.favorites.filter(item => item.pid !== pid)
      this.setData({
        favorites,
        favoritesTotal: Math.max(0, this.data.favoritesTotal - 1)
      })
      wx.showToast({ title: '已取消收藏', icon: 'none' })
    } catch (error) {
      wx.showToast({ title: '操作失败，请重试', icon: 'none' })
    } finally {
      this._removingFavorite = false
    }
  },

  /**
   * 读取本人喜欢的作品，失败保留已成功的页码。
   * @param {boolean} refresh - 是否清空喜欢列表并从第一页读取。
   * @returns {Promise<void>} 请求完成后更新喜欢的分页和错误状态。
   */
  loadLikes(refresh = false) {
    if (!app.requireLogin('/pages/mine/mine')) return Promise.resolve()
    if (this.data.likesLoading && !refresh) return Promise.resolve()
    // 喜欢独享序号和分页状态，切换分栏不会丢弃其它列表的响应。
    const requestId = this._likesRequestId = (this._likesRequestId || 0) + 1
    const page = refresh ? 1 : this.data.likesPage + 1
    const params = { page, size: 10 } // 执行卡§2 默认页长；独立于作品和收藏的配置。
    if (refresh) this.setData({ likes: [], likesPage: 0, likesTotal: 0, likesNoMore: false })
    this.setData({ likesLoading: true, likesError: false })
    // 入口为 Work[]/total，出口只补展示图片地址，不改变作品数据。
    return request('/like/mine', 'GET', params)
      .then(res => {
        if (requestId !== this._likesRequestId) return
        const works = res.data.list.map(item => ({ ...item, imageUrl: this.getFullUrl(item.vurl) }))
        const likes = refresh ? works : [...this.data.likes, ...works]
        this.setData({ likes, likesPage: page, likesTotal: res.data.total,
          likesNoMore: likes.length >= res.data.total || works.length === 0 })
      })
      .catch(() => {
        if (requestId === this._likesRequestId) this.setData({ likesError: true })
      })
      .finally(() => {
        if (requestId === this._likesRequestId) this.setData({ likesLoading: false })
      })
  },

  /** 重试喜欢的失败页；首屏失败时重新读取第一页。@returns {void} 更新喜欢列表。 */
  retryLikes() {
    this.loadLikes(this.data.likesPage === 0)
  },

  /** 并行刷新本人资料和作品，未登录不发送请求。 */
  refresh() {
    if (!app.requireLogin('/pages/mine/mine')) return Promise.resolve()
    return Promise.all([this.loadUserInfo(), this.loadWorks(true)])
  },

  /** 读取本人资料并缓存展示字段，uname 仅作兼容展示。 */
  loadUserInfo() {
    // 保存可见性期间不重读旧资料，避免返回页面时用旧开关覆盖本次提交。
    if (this.data.privacyLoading) return Promise.resolve()
    const requestId = this._profileRequestId = (this._profileRequestId || 0) + 1
    this.setData({ loading: true, profileError: false })
    return request('/user/info', 'GET')
      .then(res => {
        if (requestId !== this._profileRequestId) return
        app.setUserInfo(res.data)
        this.setData({ userInfo: app.globalData.userInfo, avatarUrl: this.getFullUrl(res.data.avatar),
          visFavorite: res.data.visFavorite == null ? true : res.data.visFavorite,
          visLike: res.data.visLike == null ? true : res.data.visLike,
          visFollowing: res.data.visFollowing == null ? true : res.data.visFollowing,
          visFollowers: res.data.visFollowers == null ? true : res.data.visFollowers, privacyReady: true })
      })
      .catch(() => {
        if (requestId === this._profileRequestId) this.setData({ profileError: true })
      })
      .finally(() => {
        if (requestId === this._profileRequestId) this.setData({ loading: false })
      })
  },

  /**
   * 保存本人收藏、喜欢、关注和粉丝可见性；四个字段一起提交，失败全部恢复。
   * @param {{currentTarget:{dataset:{field:string}},detail:{value:boolean}}} e - 开关目标和值。
   * @returns {Promise<void>} 保存结束后解除四个开关的禁用状态。
   */
  async togglePrivacy(e) {
    const field = e.currentTarget.dataset.field
    if (!['visFavorite', 'visLike', 'visFollowing', 'visFollowers'].includes(field) || this.data.privacyLoading ||
        this.data.loading || !this.data.privacyReady || !app.requireLogin('/pages/mine/mine')) return
    const previous = { visFavorite: this.data.visFavorite, visLike: this.data.visLike,
      visFollowing: this.data.visFollowing, visFollowers: this.data.visFollowers }
    const next = { ...previous, [field]: e.detail.value }
    // 作废旧资料响应，避免保存期间旧值回填；四个开关共用锁，防止互相覆盖。
    this._profileRequestId = (this._profileRequestId || 0) + 1
    this.setData({ ...next, privacyLoading: true })
    try {
      await request('/user/privacy', 'PUT', next)
      wx.showToast({ title: '已更新', icon: 'none' })
    } catch (error) {
      this.setData(previous)
      wx.showToast({ title: '更新失败，请重试', icon: 'none' })
    } finally {
      this.setData({ privacyLoading: false })
    }
  },

  /** 读取本人作品；页面不再触发筛选，refresh 为 true 时回第一页。 */
  loadWorks(refresh = false) {
    if (!app.requireLogin('/pages/mine/mine')) return Promise.resolve()
    if (this.data.worksLoading && !refresh) return Promise.resolve()
    // 筛选切换不等待旧请求；用序号丢弃迟到响应，避免混入旧状态作品。
    const requestId = this._worksRequestId = (this._worksRequestId || 0) + 1
    const page = refresh ? 1 : this.data.page + 1
    const params = { page, size: this.data.size }
    if (this.data.status) params.status = this.data.status
    if (refresh) this.setData({ list: [], page: 0, total: 0, noMore: false })
    this.setData({ worksLoading: true, worksError: false })
    // 本人身份由 Bearer token 决定，客户端不传 uid。
    return request('/pwork/mine', 'GET', params)
      .then(res => {
        if (requestId !== this._worksRequestId) return
        const works = res.data.list.map(item => ({ ...item, imageUrl: this.getFullUrl(item.vurl) }))
        const list = refresh ? works : [...this.data.list, ...works]
        this.setData({ list, page, total: res.data.total, noMore: list.length >= res.data.total || works.length === 0 })
      })
      .catch(() => {
        if (requestId === this._worksRequestId) this.setData({ worksError: true })
      })
      .finally(() => {
        if (requestId === this._worksRequestId) this.setData({ worksLoading: false })
      })
  },

  /** 切换全部或四种状态，重新请求第一页。 */
  onStatusChange(e) {
    const status = e.currentTarget.dataset.status
    if (!this.data.statuses.includes(status) || status === this.data.status) return
    this.setData({ status })
    this.loadWorks(true)
  },

  /** 重试作品当前失败页。 */
  retryWorks() {
    this.loadWorks(this.data.page === 0)
  },

  /**
   * 用卡片携带的作品 ID 进入详情，作者身份由详情响应判断。
   * @param {{currentTarget: {dataset: {pid: string}}}} e - 本人作品卡片事件。
   * @returns {void} 跳转到作品详情。
   */
  openWork(e) {
    const pid = e.currentTarget.dataset.pid
    if (typeof pid === 'string' && /^[A-Za-z0-9_-]{1,36}$/.test(pid)) {
      wx.navigateTo({ url: '/pages/workDetail/workDetail?pid=' + encodeURIComponent(pid) })
    }
  },

  /** 图片兼容绝对 URL、相对路径及缺省图。 */
  getFullUrl(path) {
    if (!path) return '/images/default.jpg'
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 预览只读头像。 */
  previewAvatar() {
    if (this.data.avatarUrl) wx.previewImage({ urls: [this.data.avatarUrl] })
  },

  /** 当前身份进入编辑页，不传他人 uid。 */
  goEditProfile() {
    if (app.requireLogin('/pages/userEdit/userEdit')) wx.navigateTo({ url: '/pages/userEdit/userEdit' })
  },

  /** 确认退出后清除凭证及资料缓存，回登录页。 */
  doLogout() {
    wx.showModal({
      title: '确认退出', content: '确定退出当前账号吗？',
      success: result => {
        if (!result.confirm) return
        app.clearLoginInfo()
        wx.reLaunch({ url: '/pages/login/login' })
      }
    })
  }
})
