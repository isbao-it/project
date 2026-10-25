/** 分类网格与分类作品流：同页切换，公开数据无需登录。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    view: 'grid', statusBarH: 0, title: '分类',
    categories: [], categoriesLoading: false, categoriesError: false,
    pcid: '', pcname: '', tab: 'latest',
    list: [], page: 0, total: 0, size: 10,
    noMore: false, loading: false, loadError: false, worksScrollTop: 0
  },
  _requestId: 0,
  _categoryRequestId: 0,

  /** 首次进入读取状态栏高度并加载分类，不提前请求作品。 */
  onLoad() {
    this.setData({ statusBarH: wx.getWindowInfo().statusBarHeight })
    this.loadCategories()
  },

  /** 页面销毁时作废在途响应，避免异步回写已销毁页面。 */
  onUnload() {
    this._requestId += 1
    this._categoryRequestId += 1
  },

  /** 读取接口的分类及真实件数；返回请求 Promise，失败保留网格并显示重试。 */
  loadCategories() {
    if (this.data.categoriesLoading) return this._categoryPromise
    const requestId = ++this._categoryRequestId
    this.setData({ categoriesLoading: true, categoriesError: false })
    this._categoryPromise = request('/category/list', 'GET')
      .then(res => {
        if (requestId !== this._categoryRequestId) return
        // 请求封装保留信封；分类没有 total，件数直接使用每项 count。
        this.setData({ categories: res.data.list })
      })
      .catch(() => {
        if (requestId === this._categoryRequestId) this.setData({ categoriesError: true })
      })
      .finally(() => {
        if (requestId === this._categoryRequestId) this.setData({ categoriesLoading: false })
      })
    return this._categoryPromise
  },

  /** 从点击事件的 pcid 找回分类；切到作品流并重新请求最新排序第一页。 */
  openCategory(event) {
    if (this.data.view !== 'grid') return
    const selected = this.data.categories.find(item => item.pcid === event.currentTarget.dataset.pcid)
    if (!selected) return
    this.setData({
      view: 'works', pcid: selected.pcid, pcname: selected.pcname,
      title: selected.pcname, tab: 'latest', worksScrollTop: 0
    })
    return this.loadWorks(true)
  },

  /** 按当前分类和排序分页；refresh 为 true 时清空旧页，返回请求 Promise。 */
  loadWorks(refresh = false) {
    if (this.data.view !== 'works') return Promise.resolve()
    if (!refresh && (this.data.loading || this.data.noMore)) return Promise.resolve()
    // 每轮请求递增版本；旧请求的成功、失败和 finally 都不能污染新分类或排序。
    const requestId = ++this._requestId
    const page = refresh ? 1 : this.data.page + 1
    if (refresh) this.setData({ list: [], page: 0, total: 0, noMore: false })
    this.setData({ loading: true, loadError: false })
    const query = {
      category: this.data.pcid,
      tab: this.data.tab,
      page, size: this.data.size
    }
    return request('/pwork/list', 'GET', query)
      .then(res => {
        if (requestId !== this._requestId) return
        // 接口作品转成卡片数据，仅补全图片地址，保留原始字符串 ID。
        const works = res.data.list.map(item => ({
          ...item,
          imageUrl: this.getFullUrl(item.vurl),
          // 头像与作品图同为相对路径，统一走 getFullUrl，缺头像时 avatarUrl 留空由模板走占位。
          avatarUrl: item.avatar ? this.getFullUrl(item.avatar) : ''
        }))
        const list = refresh ? [] : [...this.data.list]
        // 翻页期间排序可能变化，按 pid 去重；页码仍按服务端分页推进。
        const seen = new Set(list.map(item => item.pid))
        works.forEach(item => {
          if (!seen.has(item.pid)) {
            list.push(item)
            seen.add(item.pid)
          }
        })
        this.setData({
          list, page, total: res.data.total,
          noMore: page * this.data.size >= res.data.total || works.length === 0
        })
      })
      .catch(() => {
        if (requestId === this._requestId) this.setData({ loadError: true })
      })
      .finally(() => {
        if (requestId === this._requestId) this.setData({ loading: false })
      })
  },

  /** 点击事件仅接受 latest/hot；切换排序重置滚动位置并重新请求第一页。 */
  switchTab(event) {
    const tab = event.currentTarget.dataset.tab
    if (this.data.view !== 'works' || tab === this.data.tab || !['latest', 'hot'].includes(tab)) return
    this.setData({ tab, worksScrollTop: 0 })
    return this.loadWorks(true)
  },

  /** scroll-view 触底时加载下一页；失败后等用户重试，避免连续失败请求。 */
  loadMore() {
    if (!this.data.loadError) return this.loadWorks()
  },

  /** 重试失败页，只有成功响应才推进页码。 */
  retryWorks() {
    if (!this.data.loading) return this.loadWorks(this.data.page === 0)
  },

  /** 记录滚动事件的位置，供切换排序时把 scroll-view 确实复位到顶部。 */
  onWorksScroll(event) {
    this.setData({ worksScrollTop: event.detail.scrollTop })
  },

  /** 将点击事件的字符串 pid 交给详情路由；详情返回时保留本页数据和视图。 */
  goDetail(event) {
    const pid = event.currentTarget.dataset.pid
    if (typeof pid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(pid)) return
    wx.navigateTo({ url: `/pages/workDetail/workDetail?pid=${encodeURIComponent(pid)}` })
  },

  /** 将接口图片路径补全为地址；缺省图及绝对地址规则与首页相同。 */
  getFullUrl(path) {
    if (!path) return '/images/default.jpg'
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 作品流返回网格并保留数据；网格返回上一页，无栈时回首页。 */
  goBack() {
    if (this.data.view === 'works') {
      this.setData({ view: 'grid', title: '分类' })
      return
    }
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/workGallery/workGallery' })
  }
})
