/** 展示本人或他人的关注和粉丝；获赞没有接口，只展示未开放提示。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    statusBarH: 0, type: 'following', title: '关注', unsupported: false, isOthers: false,
    list: [], page: 0, size: 10, total: 0,
    loading: false, error: false, forbidden: false, noMore: false
  },

  /** 读取列表类型和目标 uid；缺省或本人 uid 沿用本人接口。@param {{type?:string,uid?:string}} options 路由参数。@returns {void} 初始化并读取列表。 */
  onLoad(options = {}) {
    const type = ['following', 'followers', 'likes'].includes(options.type) ? options.type : 'following'
    const targetUid = options.uid || ''
    this._targetUid = targetUid && targetUid !== app.globalData.uid ? targetUid : ''
    const isOthers = !!this._targetUid
    const titles = { following: '关注', followers: '粉丝', likes: '获赞' }
    this.setData({ type, isOthers, title: (isOthers ? 'TA 的' : '') + titles[type], statusBarH: wx.getWindowInfo().statusBarHeight,
      unsupported: type === 'likes' })
    // 获赞无接口：在任何请求入口之前结束，避免误用关注接口冒充。
    if (type === 'likes') return
    this.loadList(true)
  },

  /** 页面卸载后阻止移除操作的迟到响应更新页面。@returns {void} 标记页面已离开。 */
  onUnload() {
    this._unloaded = true
  },

  /**
   * 打开列表项对应用户的主页；本人则跳「我的」。
   * @param {{currentTarget:{dataset:{uid:string}}}} e 行点击事件，携带用户 uid。
   * @returns {void} 发起页面跳转。
   */
  goUser(e) {
    const uid = e.currentTarget.dataset.uid
    if (!uid) return
    if (uid === app.globalData.uid) {
      wx.switchTab({ url: '/pages/mine/mine' })
      return
    }
    wx.navigateTo({ url: '/pages/userDetail/userDetail?uid=' + encodeURIComponent(uid) })
  },

  /**
   * 二次确认后调用接口移除粉丝，成功后更新本地列表与计数；失败保留原数据。
   * @param {{currentTarget:{dataset:{uid:string}}}} e 移除入口事件，携带粉丝 uid。
   * @returns {Promise<void>} 确认与请求结束后释放防连点锁。
   */
  async removeFollower(e) {
    const uid = e.currentTarget.dataset.uid
    if (!uid || this.data.type !== 'followers' || this.data.isOthers || this._removingFollower || this._unloaded) return
    // 确认框也属于操作过程，提前加锁避免连续点击叠加确认和删除请求。
    this._removingFollower = true
    try {
      const confirmed = await new Promise(resolve => wx.showModal({
        title: '移除粉丝',
        content: '移除后对方将不再出现在你的粉丝列表中，确定吗？',
        success: r => resolve(r.confirm), fail: () => resolve(false)
      }))
      if (!confirmed || this._unloaded) return
      const res = await request('/follow/follower/' + encodeURIComponent(uid), 'DELETE')
      if (this._unloaded) return
      // 接口成功信封中的 followersCount 优先；旧响应缺少计数时本地减一且不低于零。
      const list = this.data.list.filter(item => item.uid !== uid)
      const total = res && res.data && typeof res.data.followersCount === 'number'
        ? res.data.followersCount
        : Math.max(0, this.data.total - 1)
      this.setData({ list, total })
      wx.showToast({ title: '已移除', icon: 'none' })
    } catch (error) {
      if (this._unloaded) return
      wx.showToast({ title: '移除失败，请重试', icon: 'none' })
    } finally {
      this._removingFollower = false
    }
  },

  /** 读取本人或他人关系列表，403 清除已显示的数据。@param {boolean} refresh 是否回到第一页。@returns {Promise<void>} 更新列表、分页和可见性状态。 */
  loadList(refresh = false) {
    if (this.data.type === 'likes' || this.data.unsupported) return Promise.resolve()
    if (this.data.loading || (this.data.noMore && !refresh)) return Promise.resolve()
    const target = '/pages/relation/relation?type=' + this.data.type +
      (this._targetUid ? '&uid=' + encodeURIComponent(this._targetUid) : '')
    if (!app.requireLogin(target)) return Promise.resolve()
    const page = refresh ? 1 : this.data.page + 1
    const size = this.data.size
    // 本人保留 /follow 契约；他人必须携带 uid，登录返回也需保留同一目标。
    const base = this._targetUid
      ? '/user/' + encodeURIComponent(this._targetUid)
      : '/follow'
    const url = base + (this.data.type === 'followers' ? '/followers' : '/following')
    if (refresh) this.setData({ list: [], page: 0, total: 0, noMore: false })
    this.setData({ loading: true, error: false, forbidden: false })
    // 接口交来 uid/nickname/avatar/bio；只补齐展示用头像地址，不改用户资料。
    return request(url, 'GET', { page, size })
      .then(res => {
        const users = res.data.list.map(item => ({ ...item, avatarUrl: this.getFullUrl(item.avatar) }))
        const list = refresh ? users : [...this.data.list, ...users]
        this.setData({ list, page, total: res.data.total, noMore: page * size >= res.data.total })
      })
      .catch(error => {
        if (error && error.code === 'forbidden') {
          // 可见性可能在翻页期间关闭，清空旧内容，避免继续显示已收回的列表。
          this.setData({ forbidden: true, error: false, list: [], page: 0, total: 0, noMore: true })
        } else {
          this.setData({ error: true })
        }
      })
      .finally(() => this.setData({ loading: false }))
  },

  /** 页面触底时尝试下一页，加载中或已到底由 loadList 阻止请求。 */
  onReachBottom() {
    this.loadList()
  },

  /** 首屏失败后重新请求第一页。 */
  retry() {
    this.loadList(true)
  },

  /** 头像兼容绝对及相对地址；空地址交给模板渲染渐变占位。 */
  getFullUrl(path) {
    if (!path) return ''
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 返回上一页；直接进入没有上一页时切回「我的」。 */
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/mine/mine' })
  }
})
