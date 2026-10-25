/** 编辑本人作品的标题、描述与分类；封面原值随四字段全量提交。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    statusBarH: 0, work: null, imageUrl: '',
    title: '', desc: '', categoryId: '', categories: [],
    loading: true, categoryLoading: false, categoryError: false, saving: false
  },

  /**
   * 接收字符串 pid，并行读取作品和分类，作品归属只比较全局 uid。
   * @param {{pid?: string}} options - 路由传入的作品 ID。
   * @returns {Promise<void[]>|void} 两类读取各自处理失败。
   */
  onLoad(options = {}) {
    this.setData({ statusBarH: wx.getWindowInfo().statusBarHeight || 0 })
    this._pid = options.pid
    if (typeof this._pid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(this._pid)) {
      this.rejectEdit()
      return
    }
    return Promise.all([this.loadWork(), this.loadCategories()])
  },

  /**
   * 读取作品并预填草稿；封面展示补全 URL，提交仍保留 work.vurl 原值。
   * @returns {Promise<void>} 完成归属检查及表单预填。
   */
  async loadWork() {
    try {
      const res = await request('/pwork/' + encodeURIComponent(this._pid), 'GET')
      if (this._unloaded) return
      const work = res.data && res.data.work
      const me = app.globalData.uid
      if (!me || !work || work.uid !== me) {
        this.rejectEdit()
        return
      }
      const imageUrl = /^https?:\/\//.test(work.vurl) ? work.vurl :
        work.vurl ? app.globalData.baseUrl + (work.vurl.startsWith('/') ? work.vurl : '/' + work.vurl) : ''
      // 输入为详情中的原始作品；仅交付表单草稿，不改写作品或全局缓存。
      this.setData({ work, imageUrl, title: work.wtitle || '', desc: work.wdesc || '',
        categoryId: work.pcid || '', loading: false })
    } catch (error) {
      if (!this._unloaded) this.rejectEdit()
    }
  },

  /**
   * 读取分类胶囊；失败可单独重试，不清空已填草稿。
   * @returns {Promise<void>} 更新分类及加载状态。
   */
  async loadCategories() {
    if (this.data.categoryLoading) return
    this.setData({ categoryLoading: true, categoryError: false })
    try {
      const res = await request('/category/list', 'GET')
      if (!this._unloaded) this.setData({ categories: res.data.list })
    } catch (error) {
      if (!this._unloaded) this.setData({ categoryError: true })
    } finally {
      if (!this._unloaded) this.setData({ categoryLoading: false })
    }
  },

  /**
   * 无作品或无编辑权限时提示，留出 1.5 秒阅读时间再返回。
   * @returns {void} 禁用表单并安排返回。
   */
  rejectEdit() {
    this.setData({ work: null, loading: false })
    wx.showToast({ title: '作品不存在或无权编辑', icon: 'none', duration: 1500 })
    this._backTimer = setTimeout(() => wx.navigateBack(), 1500)
  },

  /**
   * 同步标题输入，不写入持久化存储。
   * @param {{detail: {value: string}}} e - 标题框当前值。
   * @returns {void} 更新标题草稿。
   */
  onTitleInput(e) {
    this.setData({ title: e.detail.value })
  },

  /**
   * 同步描述输入，不修改原始作品对象。
   * @param {{detail: {value: string}}} e - 描述框当前值。
   * @returns {void} 更新描述草稿。
   */
  onDescInput(e) {
    this.setData({ desc: e.detail.value })
  },

  /**
   * 单选接口返回的分类，提交期间保持表单稳定。
   * @param {{currentTarget: {dataset: {pcid: string}}}} e - 分类胶囊事件。
   * @returns {void} 更新所选分类 ID。
   */
  selectCategory(e) {
    if (this.data.saving) return
    const categoryId = e.currentTarget.dataset.pcid
    if (this.data.categories.some(item => item.pcid === categoryId)) this.setData({ categoryId })
  },

  /**
   * 校验草稿并全量保存四字段；成功等待 800ms 后返回，服务端重新审核。
   * @returns {Promise<void>} 更新提交状态或显示固定中文错误提示。
   */
  async save() {
    if (this.data.saving || this.data.loading || !this.data.work) return
    const { work, desc, categoryId } = this.data
    if (!app.globalData.uid || work.uid !== app.globalData.uid) {
      wx.showToast({ title: '无权编辑该作品', icon: 'none' })
      return
    }
    const title = this.data.title.trim()
    if (!title || title.length > 100) {
      wx.showToast({ title: '请输入1–100字标题', icon: 'none' })
      return
    }
    if (desc.length > 255) {
      wx.showToast({ title: '描述不能超过255字', icon: 'none' })
      return
    }
    if (!categoryId || !this.data.categories.some(item => item.pcid === categoryId)) {
      wx.showToast({ title: '请选择分类', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      // 接口全量覆盖：图片必须沿用原始路径，不能提交预览补全后的地址。
      await request('/pwork/' + encodeURIComponent(this._pid), 'PUT', {
        title, desc, imageUrl: work.vurl, categoryId
      })
      if (this._unloaded) return
      wx.showToast({ title: '已保存，等待重新审核', icon: 'none' })
      // 成功后保持禁用直到返回，避免 800ms 提示期间再次发起保存。
      this._backTimer = setTimeout(() => wx.navigateBack(), 800)
    } catch (error) {
      if (this._unloaded) return
      const messages = {
        content_rejected: '内容未通过安全检查',
        invalid_request: '请检查填写内容',
        forbidden: '无权编辑该作品'
      }
      wx.showToast({ title: messages[error && error.code] || '保存失败，请重试', icon: 'none' })
      this.setData({ saving: false })
    }
  },

  /**
   * 自绘返回按钮离开表单，不保存草稿。
   * @returns {void} 返回上一页。
   */
  goBack() {
    wx.navigateBack()
  },

  /**
   * 页面销毁后丢弃读取结果，并取消定时返回以免误退上一页。
   * @returns {void} 清理页面生命周期状态。
   */
  onUnload() {
    this._unloaded = true
    clearTimeout(this._backTimer)
  }
})
