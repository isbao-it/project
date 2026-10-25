/** 公开作品详情：展示作品、作者与 EXIF，支持评论、互动及大图预览。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    work: null, exif: null, author: null, isOwner: false,
    imageUrl: '', avatarUrl: '', exifItems: [],
    loading: false, errorMessage: '', canRetry: false,
    statusBarH: 0, subtitle: '', currentUid: '', loggedIn: false,
    comments: [], commentTotal: 0, commentPage: 0, commentLoading: false,
    commentInput: '', commentError: '', submitting: false,
    liked: false, likeCount: 0, favorited: false, favoriteCount: null,
    viewCount: 0,
    following: false, followers: null, similarList: [],
    likeLoading: false, favoriteLoading: false, followLoading: false
  },

  /**
   * 接收路由中的原始字符串 pid，非法或缺失时不请求接口。
   * @param {{pid?: string}} options - 微信传入的路由参数。
   * @returns {void} 启动三个独立读取。
   */
  onLoad(options = {}) {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarH: info.statusBarHeight || 0 })
    // 详情先同步保存字符串 pid；三类读取各自处理错误，不串行等待。
    this.loadDetail(options.pid)
    this.loadComments(true)
    this.loadSimilar()
  },

  /**
   * 回到页面时同步登录身份；作者从编辑页返回后重读作品状态。
   * @returns {void} 更新页面中的身份展示字段。
   */
  onShow() {
    const loggedIn = app.checkLogin()
    this.setData({ loggedIn, currentUid: loggedIn ? app.globalData.uid : '' })
    if (this._refreshAfterEdit) {
      this._refreshAfterEdit = false
      this.loadDetail(this._pid)
    }
  },

  /**
   * 离页作废在途请求，避免旧作品回填。
   * @returns {void} 标记页面失效。
   */
  onUnload() {
    this._unloaded = true
    this._requestId = (this._requestId || 0) + 1
  },

  /**
   * 读取 data.work/exif/author；浏览量直接显示服务端已增加后的值。
   * @param {string} pid - 不转为数值的作品 ID。
   * @returns {Promise} 本次读取完成后结束的 Promise。
   */
  loadDetail(pid) {
    const requestId = this._requestId = (this._requestId || 0) + 1
    this._pid = pid
    this.setData({ work: null, exif: null, author: null, isOwner: false, imageUrl: '', avatarUrl: '', exifItems: [], errorMessage: '', canRetry: false,
      subtitle: '', liked: false, likeCount: 0, favorited: false, favoriteCount: null, following: false, followers: null })
    if (typeof pid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(pid)) {
      this.setData({ loading: false, errorMessage: '作品不存在或不可访问' })
      return Promise.resolve()
    }
    this.setData({ loading: true })
    return request('/pwork/' + encodeURIComponent(pid), 'GET')
      .then(res => {
        if (requestId !== this._requestId) return
        const { work, exif, author } = res.data
        const me = app.globalData.uid
        const isOwner = !!(me && work && work.uid === me)
        const fields = [
          { key: 'focal', label: '焦距' },
          { key: 'aperture', label: '光圈' },
          { key: 'shutter', label: '快门' },
          { key: 'iso', label: 'ISO' },
          { key: 'lens', label: '镜头' },
          { key: 'camera', label: '相机' }
        ]
        const exifItems = fields.map(field => {
          const raw = exif && exif[field.key]
          let value = '—'
          if (raw != null && raw !== '') {
            value = field.key === 'focal' ? `${raw} mm` : field.key === 'aperture' ? `f/${raw}` : String(raw)
          }
          return { ...field, value }
        })
        const params = [exif && exif.aperture != null && exif.aperture !== '' ? `f/${exif.aperture}` : '',
          exif && exif.shutter, exif && exif.iso != null && exif.iso !== '' ? `ISO ${exif.iso}` : '']
          .filter(value => value != null && value !== '').join(' ')
        const subtitle = [work.pcname, work.cwtime ? String(work.cwtime).slice(0, 10) : '', params]
          .filter(value => value != null && value !== '').join(' · ')
        // 收藏数与收藏态直接取服务端详情，避免进页面时先空着、点一次才出现。
        this.setData({ work, exif, author, isOwner, exifItems, subtitle, likeCount: work.likep,
          viewCount: work.viewcount || 0,
          favoriteCount: typeof work.favoriteCount === 'number' ? work.favoriteCount : null,
          favorited: work.favorited === true,
          imageUrl: this.getFullUrl(work.vurl),
          avatarUrl: author && author.avatar ? this.getFullUrl(author.avatar) : '' })
      })
      .catch(error => {
        if (requestId !== this._requestId) return
        const unavailable = error && (error.code === 'not_found' || error.code === 'invalid_request')
        this.setData({ errorMessage: unavailable ? '作品不存在或不可访问' : '作品加载失败，请重试', canRetry: !unavailable })
      })
      .finally(() => {
        if (requestId === this._requestId) this.setData({ loading: false })
      })
  },

  /**
   * 仅重试暂时性失败，加载期间防止重复增加浏览量。
   * @returns {void} 按原详情契约决定是否重读。
   */
  retryDetail() {
    if (this.data.canRetry && !this.data.loading) this.loadDetail(this._pid)
  },

  /**
   * 仅本人作品可打开管理菜单；编辑返回后重新读取服务端状态。
   * @returns {void} 按作品状态展示可见性切换、编辑和删除操作。
   */
  openMore() {
    const me = app.globalData.uid
    const work = this.data.work
    if (!this.data.isOwner || !me || !work || work.uid !== me || this._deletingWork) return
    const pid = this._pid

    // 只有「展示」「隐藏」允许作者自己切换；待审核/已驳回必须走审核。
    const canToggle = work.wstatus === '展示' || work.wstatus === '隐藏'
    const toggleLabel = work.wstatus === '展示' ? '设为隐藏' : '设为展示'
    const actions = canToggle ? [toggleLabel, '编辑作品', '删除作品'] : ['编辑作品', '删除作品']

    wx.showActionSheet({
      itemList: actions,
      success: result => {
        if (this._unloaded) return
        const label = actions[result.tapIndex]
        if (label === toggleLabel && canToggle) {
          this.toggleVisibility(work.wstatus === '展示' ? '隐藏' : '展示')
        } else if (label === '编辑作品') {
          this._refreshAfterEdit = true
          wx.navigateTo({ url: '/pages/workEdit/workEdit?pid=' + encodeURIComponent(pid) })
        } else if (label === '删除作品') {
          this.deleteWork()
        }
      }
    })
  },

  /**
   * 二次确认后切换本人作品可见性；成功重读详情，失败提示固定中文。
   * @param {string} status - 目标状态，仅允许「展示」或「隐藏」。
   * @returns {Promise<void>} 状态更新及详情刷新完成后结束。
   */
  async toggleVisibility(status) {
    if (this._togglingStatus || !['展示', '隐藏'].includes(status)) return
    // 确认弹窗也属于本次操作，提前加锁避免连点产生多个确认与请求。
    this._togglingStatus = true
    try {
      const label = status === '隐藏' ? '隐藏（下架）' : '展示（重新上架）'
      const confirmed = await new Promise(resolve => wx.showModal({
        title: '修改作品状态',
        content: '确定将作品设为「' + label + '」吗？',
        success: result => resolve(result.confirm), fail: () => resolve(false)
      }))
      if (!confirmed || this._unloaded) return
      wx.showLoading({ title: '处理中', mask: true })
      await request('/pwork/' + encodeURIComponent(this._pid) + '/status', 'PUT', { status })
      wx.hideLoading()
      if (this._unloaded) return
      wx.showToast({ title: status === '隐藏' ? '已下架' : '已上架', icon: 'none' })
      await this.loadDetail(this._pid) // 重读服务端详情，刷新状态胶囊。
    } catch (error) {
      wx.hideLoading()
      if (this._unloaded) return
      const messages = {
        conflict: '当前状态不允许切换',
        not_found: '作品不存在或无权修改'
      }
      wx.showToast({ title: messages[error && error.code] || '操作失败，请重试', icon: 'none' })
    } finally {
      this._togglingStatus = false
    }
  },

  /**
   * 二次确认后删除当前本人作品；成功离开详情，失败仅提示固定中文。
   * @returns {Promise<void>} 删除请求完成后结束。
   */
  async deleteWork() {
    const work = this.data.work
    const me = app.globalData.uid
    if (!this.data.isOwner || !me || !work || work.uid !== me || this._deletingWork) return
    this._deletingWork = true
    try {
      const confirmed = await new Promise(resolve => wx.showModal({
        title: '删除作品', content: '确定删除该作品吗？删除后无法恢复。',
        success: result => resolve(result.confirm), fail: () => resolve(false)
      }))
      if (!confirmed || this._unloaded || app.globalData.uid !== work.uid) return
      await request('/pwork/' + encodeURIComponent(this._pid), 'DELETE')
      if (this._unloaded) return
      wx.showToast({ title: '已删除', icon: 'success' })
      wx.navigateBack()
    } catch (error) {
      if (!this._unloaded) this.showActionError(error,
        error && error.code === 'forbidden' ? '无权删除该作品' :
          error && error.code === 'not_found' ? '作品不存在或已删除' : '删除失败，请重试')
    } finally {
      this._deletingWork = false
    }
  },

  /**
   * 读取评论页；首页替换列表，后续页按 cid 去重追加。
   * @param {boolean} refresh - 是否重新读取首页。
   * @returns {Promise<void>} 请求完成后同步列表、总数及页码。
   */
  async loadComments(refresh = false) {
    if (this.data.commentLoading || this.data.submitting || this._deletingCid ||
        !/^[A-Za-z0-9_-]{1,36}$/.test(this._pid || '')) return
    if (!refresh && this.data.commentPage > 0 && this.data.comments.length >= this.data.commentTotal) return
    const pid = this._pid
    const page = refresh ? 1 : this.data.commentPage + 1
    this.setData({ commentLoading: true, commentError: '' })
    try {
      // 输入为平铺的服务端评论；保留 content，不从历史数据库列名取值。
      const res = await request('/comment/list', 'GET', { pid, page, size: 10 })
      if (this._unloaded || pid !== this._pid) return
      const incoming = res.data.list.map(item => {
        const author = item.author || {}
        return { ...item,
          nickname: author.nickname || '未知用户',
          avatarUrl: this.getFullUrl(author.avatar),
          timeText: item.createdAt ? String(item.createdAt).replace('T', ' ').slice(0, 16) : '' }
      })
      const comments = refresh ? [] : this.data.comments.slice()
      // 新评论会移动服务端分页边界，按 cid 去重，避免翻页重复展示。
      incoming.forEach(item => { if (!comments.some(comment => comment.cid === item.cid)) comments.push(item) })
      this.setData({ comments, commentTotal: res.data.total, commentPage: page })
    } catch (error) {
      if (!this._unloaded && pid === this._pid) this.setData({ commentError: '评论加载失败，请重试' })
    } finally {
      if (!this._unloaded && pid === this._pid) this.setData({ commentLoading: false })
    }
  },

  /**
   * 校验并提交输入内容；服务端确认 cid 后插入本人评论，失败保留草稿。
   * @returns {Promise<void>} 发送完成后更新列表和提交状态。
   */
  async submitComment() {
    if (!app.requireLogin() || !this.data.work || this.data.submitting || this.data.commentLoading || this._deletingCid) return
    const content = this.data.commentInput.trim()
    if (!content || content.length > 500) {
      wx.showToast({ title: '请输入1–500字评论', icon: 'none' })
      return
    }
    const pid = this._pid
    const uid = app.globalData.uid
    const profile = app.globalData.userInfo || {}
    this.setData({ submitting: true })
    try {
      const res = await request('/comment', 'POST', { pid, content })
      if (this._unloaded || pid !== this._pid) return
      // 接口仅确认 cid；本人昵称取登录缓存，时间先标“刚刚”，刷新后取服务端时间。
      const comment = { pid, cid: res.data.cid, uid, content,
        nickname: profile.nickname || app.globalData.uname || '我',
        avatarUrl: this.getFullUrl(profile.avatar), timeText: '刚刚' }
      this.setData({ comments: [comment, ...this.data.comments], commentTotal: this.data.commentTotal + 1, commentInput: '' })
    } catch (error) {
      if (!this._unloaded) this.showActionError(error, '评论发送失败，请重试')
    } finally {
      if (!this._unloaded) this.setData({ submitting: false })
    }
  },

  /**
   * 二次确认后删除本人评论，成功后重读首页以修正分页边界。
   * @param {{currentTarget: {dataset: {cid: string}}}} e - 删除按钮事件。
   * @returns {Promise<void>} 删除及列表刷新完成后结束。
   */
  async deleteComment(e) {
    if (!app.requireLogin() || this.data.commentLoading || this.data.submitting || this._deletingCid) return
    const cid = e.currentTarget.dataset.cid
    const comment = this.data.comments.find(item => item.cid === cid)
    if (!comment || comment.uid !== app.globalData.uid) return
    this._deletingCid = cid
    try {
      const confirmed = await new Promise(resolve => wx.showModal({ title: '删除评论', content: '确定删除这条评论吗？',
        success: result => resolve(result.confirm), fail: () => resolve(false) }))
      if (!confirmed || this._unloaded || !app.requireLogin()) return
      await request('/comment/' + encodeURIComponent(cid), 'DELETE')
      if (this._unloaded) return
      this.setData({ comments: this.data.comments.filter(item => item.cid !== cid), commentTotal: Math.max(0, this.data.commentTotal - 1), commentPage: 0 })
      // 删除会让后续页向前移动，重读首页防止下一页永久漏掉边界评论。
      this._deletingCid = ''
      await this.loadComments(true)
    } catch (error) {
      if (!this._unloaded) this.showActionError(error, error && error.code === 'forbidden' ? '无权删除这条评论' :
        error && error.code === 'not_found' ? '评论不存在或已删除' : '评论删除失败，请重试')
    } finally {
      this._deletingCid = ''
    }
  },

  /**
   * 按当前确认状态点赞或取消；仅用接口 liked/count 更新按钮与计数。
   * @returns {Promise<void>} 服务端确认后同步点赞状态。
   */
  async toggleLike() {
    if (!app.requireLogin() || !this.data.work || this.data.likeLoading) return
    this.setData({ likeLoading: true })
    try {
      const res = await request('/like/' + encodeURIComponent(this._pid), this.data.liked ? 'DELETE' : 'POST')
      if (this._unloaded) return
      this.setData({ liked: res.data.liked, likeCount: res.data.count, 'work.likep': res.data.count })
    } catch (error) {
      if (!this._unloaded) this.showActionError(error, '点赞操作失败，请重试')
    } finally {
      if (!this._unloaded) this.setData({ likeLoading: false })
    }
  },

  /**
   * 收藏或取消收藏；计数在首次收到服务端 count 后才向视图交付。
   * @returns {Promise<void>} 服务端确认后同步收藏状态。
   */
  async toggleFavorite() {
    if (!app.requireLogin() || !this.data.work || this.data.favoriteLoading) return
    this.setData({ favoriteLoading: true })
    try {
      const res = await request('/favorite/' + encodeURIComponent(this._pid), this.data.favorited ? 'DELETE' : 'POST')
      if (this._unloaded) return
      this.setData({ favorited: res.data.favorited, favoriteCount: res.data.count })
    } catch (error) {
      if (!this._unloaded) this.showActionError(error, '收藏操作失败，请重试')
    } finally {
      if (!this._unloaded) this.setData({ favoriteLoading: false })
    }
  },

  /**
   * 关注详情作者或取消；拦截本人，按钮与粉丝数均取接口响应。
   * @returns {Promise<void>} 服务端确认后同步关注状态。
   */
  async toggleFollow() {
    if (!app.requireLogin() || !this.data.work || this.data.followLoading) return
    const uid = (this.data.author && this.data.author.uid) || this.data.work.uid
    if (!uid || uid === app.globalData.uid) {
      wx.showToast({ title: uid ? '不能关注自己' : '作者信息暂不可用', icon: 'none' })
      return
    }
    this.setData({ followLoading: true })
    try {
      const res = await request('/follow/' + encodeURIComponent(uid), this.data.following ? 'DELETE' : 'POST')
      if (this._unloaded) return
      this.setData({ following: res.data.following, followers: res.data.followers })
    } catch (error) {
      if (!this._unloaded) this.showActionError(error, error && error.code === 'conflict' ? '不能关注自己' : '关注操作失败，请重试')
    } finally {
      if (!this._unloaded) this.setData({ followLoading: false })
    }
  },

  /**
   * 读取最多六个同款作品；仅消费 list，空列表或失败时隐藏整个区域。
   * @returns {Promise<void>} 读取后向视图交付带完整图片地址的作品列表。
   */
  async loadSimilar() {
    if (!/^[A-Za-z0-9_-]{1,36}$/.test(this._pid || '')) return
    const pid = this._pid
    try {
      const res = await request('/pwork/similar/' + encodeURIComponent(pid), 'GET', { limit: 6 })
      if (this._unloaded || pid !== this._pid) return
      this.setData({ similarList: res.data.list.map(item => ({ ...item, imageUrl: this.getFullUrl(item.vurl) })) })
    } catch (error) {
      if (!this._unloaded && pid === this._pid) {
        this.setData({ similarList: [] })
        this.showActionError(error, '同款作品加载失败，请稍后重试')
      }
    }
  },

  /**
   * 输入事件只更新草稿，不写入持久化存储。
   * @param {{detail: {value: string}}} e - 输入框当前内容。
   * @returns {void} 同步页面草稿。
   */
  onCommentInput(e) {
    this.setData({ commentInput: e.detail.value })
  },

  /**
   * 未登录点击输入区域时交给应用登录守卫处理回跳。
   * @returns {void} 由应用决定是否跳转。
   */
  ensureCommentLogin() {
    app.requireLogin()
  },

  /**
   * 四连评论入口滚动到评论区，游客也能阅读。
   * @returns {void} 调用微信页面滚动能力。
   */
  scrollToComments() {
    wx.pageScrollTo({ selector: '#comments', duration: 250 })
  },

  /**
   * 列表底部加载下一页，不直接把点击事件当作 refresh 参数。
   * @returns {Promise<void>} 下一页读取任务。
   */
  loadMoreComments() {
    return this.loadComments(false)
  },

  /**
   * 首屏读取失败重读首页，其余读取失败重试未成功的下一页。
   * @returns {Promise<void>} 评论重试任务。
   */
  retryComments() {
    return this.loadComments(this.data.commentPage === 0)
  },

  /**
   * 同款缩略图的字符串 pid 导向现有详情页，不改变原画廊状态。
   * @param {{currentTarget: {dataset: {pid: string}}}} e - 同款作品点击事件。
   * @returns {void} 合法 pid 才发起跳转。
   */
  openSimilar(e) {
    const pid = e.currentTarget.dataset.pid
    if (typeof pid === 'string' && /^[A-Za-z0-9_-]{1,36}$/.test(pid)) {
      wx.navigateTo({ url: '/pages/workDetail/workDetail?pid=' + encodeURIComponent(pid) })
    }
  },

  /**
   * 点击仅提示微信原生分享入口，不宣称分享成功。
   * @returns {void} 显示固定中文提示。
   */
  promptShare() {
    wx.showToast({ title: '请点右上角分享', icon: 'none' })
  },

  /**
   * 微信原生分享回调：交付当前作品的分享信息。
   * @returns {{title: string|undefined, path: string, imageUrl: string}} 标题、路由和完整图片地址。
   */
  onShareAppMessage() {
    const work = this.data.work || {}
    return { title: work.wtitle, path: '/pages/workDetail/workDetail?pid=' + encodeURIComponent(this._pid), imageUrl: this.data.imageUrl }
  },

  /**
   * 页面错误只显示调用方固定中文文案；401 的跳登录由请求层完成。
   * @param {{code?: string}|null|undefined} error - 请求拒绝对象，仅检查错误码。
   * @param {string} message - 调用方写死的中文提示。
   * @returns {void} 显示页面层提示。
   */
  showActionError(error, message) {
    if (!error || error.code !== 'unauthorized') wx.showToast({ title: message, icon: 'none' })
  },

  /**
   * 将相对图片地址补为完整 URL，绝对地址保持原值。
   * @param {string|null|undefined} path - 服务端图片路径。
   * @returns {string} 完整图片地址，缺失时为空字符串。
   */
  getFullUrl(path) {
    if (!path) return ''
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /**
   * 将当前展示的完整图片地址交给微信预览。
   * @returns {void} 有作品且有图片时发起预览。
   */
  previewImage() {
    if (this.data.work && this.data.imageUrl) wx.previewImage({ current: this.data.imageUrl, urls: [this.data.imageUrl] })
  },

  /**
   * 打开头像对应主页；缺失 uid 不跳转，本人回到我的页。
   * @param {{currentTarget:{dataset:{uid?:string}}}} e - 作者或评论头像事件。
   * @returns {void} 按身份选择页面路由。
   */
  goAuthor(e) {
    const uid = e.currentTarget.dataset.uid
    const me = app.globalData.uid
    if (!uid) return
    if (uid === me) { wx.switchTab({ url: '/pages/mine/mine' }); return }
    wx.navigateTo({ url: '/pages/userDetail/userDetail?uid=' + encodeURIComponent(uid) })
  },

  /**
   * 返回原画廊；无上级页面时回到已注册的首页。
   * @returns {void} 返回上一页，失败时切回首页。
   */
  goBack() {
    wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/workGallery/workGallery' }) })
  }
})
