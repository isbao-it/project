/** 单图安全上传后发布待审核作品；身份、审核状态均由服务端决定。 */
const app = getApp()
const PAGE_URL = '/pages/workList/workList'
const MAX_IMAGE_SIZE = 10 * 1024 * 1024 // 冻结 v1.1：单张图片最多 10MB。
const TITLE_LIMIT = 100 // 冻结 v1.1 §1.3：与 pwork.wtitle 的 varchar(100) 一致。
const DESC_LIMIT = 255 // 与 pwork.wdesc 的 varchar(255) 一致；空串仍须随发布体发送。
const EXIF_FIELDS = [
  { key: 'focal', label: '焦距', unit: ' mm' },
  { key: 'aperture', label: '光圈', unit: '' },
  { key: 'shutter', label: '快门', unit: '' },
  { key: 'iso', label: 'ISO', unit: '' },
  { key: 'lens', label: '镜头', unit: '' },
  { key: 'camera', label: '相机', unit: '' }
]

Page({
  data: {
    title: '', desc: '', titleLength: 0, descLength: 0,
    previewUrl: '', imageUrl: '', exif: null, exifItems: [],
    choosing: false, uploading: false, submitting: false,
    categories: [], categoryIndex: -1, categoryName: '',
    categoriesLoaded: false, categoriesLoading: false,
    categoryError: '', uploadError: '', submitError: ''
  },

  /** 直接进入或切换 tab 时均检查登录，并同步入口高亮。 */
  onShow() {
    if (this.getTabBar()) this.getTabBar().setSelected('/pages/workList/workList')
    if (!app.requireLogin(PAGE_URL)) return
    const auth = app.getAuthHeader().Authorization
    // tab 页常驻内存；换账号后不能继续展示或提交上一个作者的草稿。
    if (this._ownerAuth !== auth) {
      this._ownerAuth = auth
      this.resetForm()
      this.setData({ categories: [], categoriesLoaded: false, categoriesLoading: false, categoryError: '' })
    }
    if (!this.data.categoriesLoaded && !this.data.categoriesLoading) this.loadCategories()
  },

  /** 页面销毁后忽略迟到的请求和选图回调。 */
  onUnload() {
    this._unloaded = true
  },

  /** 判断回调是否仍属于当前登录凭证，防止旧请求影响新会话。 */
  isCurrentSession(auth) {
    return !this._unloaded && auth === this._ownerAuth && auth === app.getAuthHeader().Authorization
  },

  /** 读取分类字典；失败和空列表均保持不可发布，允许手动重新读取。 */
  loadCategories() {
    if (!app.requireLogin(PAGE_URL) || this.data.categoriesLoading || this.data.submitting) return
    const header = app.getAuthHeader()
    this.setData({ categoriesLoading: true, categoriesLoaded: false, categoryError: '' })
    wx.request({
      url: `${app.globalData.baseUrl}/category/list`, method: 'GET', header,
      success: res => {
        if (!this.isCurrentSession(header.Authorization)) return
        const body = res.data
        const list = body && body.data && body.data.list
        if (res.statusCode !== 200 || !this.isOkEnvelope(body)) {
          this.showFailure('categoryError', res.statusCode, body && body.code, '分类加载失败，请重新加载')
          return
        }
        if (!Array.isArray(list) || list.some(item => !item ||
            typeof item.pcid !== 'string' || !/^[A-Za-z0-9_-]{1,36}$/.test(item.pcid) ||
            typeof item.pcname !== 'string' || !item.pcname.trim())) {
          this.showFailure('categoryError', 0, '', '分类数据异常，请重新加载')
          return
        }
        this.setData({
          categories: list.map(item => ({ pcid: item.pcid, pcname: item.pcname })),
          categoriesLoaded: true, categoryIndex: -1, categoryName: '',
          categoryError: list.length ? '' : '暂无可用分类，请稍后重新加载'
        })
      },
      fail: () => {
        if (this.isCurrentSession(header.Authorization)) {
          this.showFailure('categoryError', 0, '', '网络异常，分类加载失败，请重试')
        }
      },
      complete: () => {
        if (this.isCurrentSession(header.Authorization)) this.setData({ categoriesLoading: false })
      }
    })
  },

  /** 选择一张原图并预览；只有确认新选择后才清旧上传数据，取消保留草稿。 */
  chooseImage() {
    if (!app.requireLogin(PAGE_URL) || this.data.choosing || this.data.uploading || this.data.submitting) return
    const auth = app.getAuthHeader().Authorization
    this.setData({ choosing: true })
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sizeType: ['original'], sourceType: ['album', 'camera'],
      success: res => {
        if (!this.isCurrentSession(auth) || !app.requireLogin(PAGE_URL)) return
        const file = res.tempFiles && res.tempFiles[0]
        if (!file) return
        // 新图无论是否合法，都不能沿用上一张的安全 URL 和 EXIF。
        this.setData({ previewUrl: file.tempFilePath || '', imageUrl: '', exif: null, exifItems: [], uploadError: '', submitError: '' })
        if (!file.tempFilePath || !Number.isFinite(file.size) || file.size <= 0) {
          this.showFailure('uploadError', 0, '', '图片文件无效，请重新选择')
          return
        }
        if (file.size > MAX_IMAGE_SIZE) {
          this.showFailure('uploadError', 413)
          return
        }
        // 临时路径不保证带扩展名，真实格式和损坏检测交给服务端解码。
        this.uploadImage(file.tempFilePath)
      },
      fail: error => {
        if (this.isCurrentSession(auth) && !/cancel/i.test(error.errMsg || '')) {
          this.showFailure('uploadError', 0, '', '选图失败，请检查相册或相机权限后重试')
        }
      },
      complete: () => {
        if (this.isCurrentSession(auth)) this.setData({ choosing: false })
      }
    })
  },

  /** 用 file 字段上传本地图片，校验安全剥离标记后才允许使用服务器地址。 */
  uploadImage(filePath) {
    if (!app.requireLogin(PAGE_URL) || this.data.uploading || this.data.submitting) return
    const header = app.getAuthHeader()
    this.setData({ uploading: true, imageUrl: '', exif: null, exifItems: [], uploadError: '' })
    wx.uploadFile({
      url: `${app.globalData.baseUrl}/upload/work`, filePath, name: 'file', header,
      success: res => {
        if (!this.isCurrentSession(header.Authorization)) return
        let body
        try { body = JSON.parse(res.data) } catch (error) {
          this.showFailure('uploadError', res.statusCode, '', '上传响应异常，请重新选择图片')
          return
        }
        if (res.statusCode !== 200 || !this.isOkEnvelope(body)) {
          this.showFailure('uploadError', res.statusCode, body && body.code, '上传失败，请重新选择图片')
          return
        }
        const data = body.data
        if (!data || data.exifStripped !== true || typeof data.url !== 'string' ||
            !/^(https?:\/\/[^\s]+|\/(?!\/)[^\s]+)$/.test(data.url) || data.url.length > 255 ||
            !data.exif || typeof data.exif !== 'object' || Array.isArray(data.exif)) {
          this.showFailure('uploadError', 0, '', '安全图片数据不完整，请重新选择图片')
          return
        }
        // 输入来自上传信封；输出只取六项白名单，不透传 GPS、序列号等元数据。
        const exif = {}
        const exifItems = EXIF_FIELDS.map(field => {
          const value = data.exif[field.key] == null ? null : data.exif[field.key]
          exif[field.key] = value
          const text = value === null || value === '' ? '—' :
            (field.key === 'aperture' ? `f/${value}` : `${value}${field.unit}`)
          return { key: field.key, label: field.label, value: text }
        })
        const previewUrl = /^https?:\/\//.test(data.url) ? data.url : `${app.globalData.baseUrl}${data.url}`
        this.setData({ imageUrl: data.url, previewUrl, exif, exifItems })
      },
      fail: () => {
        if (this.isCurrentSession(header.Authorization)) {
          this.showFailure('uploadError', 0, '', '网络异常，上传失败，请重新选择图片')
        }
      },
      complete: () => {
        if (this.isCurrentSession(header.Authorization)) this.setData({ uploading: false })
      }
    })
  },

  /** 点击当前图片打开预览；上传前为本地图，上传成功后为服务器安全图。 */
  previewImage() {
    if (this.data.previewUrl) wx.previewImage({ urls: [this.data.previewUrl] })
  },

  /** 标题、描述分别更新字数；不接受事件写入其它提交字段。 */
  onInputChange(e) {
    if (this.data.submitting) return
    const field = e.currentTarget.dataset.field
    if (field !== 'title' && field !== 'desc') return
    this.setData({ [field]: e.detail.value, [`${field}Length`]: Array.from(e.detail.value).length, submitError: '' })
  },

  /** 保存分类下标供展示；发布时始终取对应的字符串 pcid。 */
  onCategoryChange(e) {
    if (this.data.submitting || this.data.categoriesLoading || !this.data.categoriesLoaded) return
    const index = Number(e.detail.value)
    if (!Number.isInteger(index) || !this.data.categories[index]) return
    this.setData({ categoryIndex: index, categoryName: this.data.categories[index].pcname, submitError: '' })
  },

  /** 校验完整表单后只发一次发布请求；失败保留输入，绝不自动重发。 */
  submitForm() {
    if (!app.requireLogin(PAGE_URL) || this.data.submitting || this.data.choosing || this.data.uploading) return
    const { title, desc, imageUrl, exif, categories, categoryIndex } = this.data
    if (!imageUrl || !exif) {
      this.showFailure('submitError', 0, '', '请先选择图片并完成安全上传')
      return
    }
    if (!title.trim() || Array.from(title).length > TITLE_LIMIT) {
      this.showFailure('submitError', 0, '', '标题须为1至100字且不能全为空白')
      return
    }
    if (Array.from(desc).length > DESC_LIMIT) {
      this.showFailure('submitError', 0, '', '描述不能超过255字')
      return
    }
    const category = categories[categoryIndex]
    if (!this.data.categoriesLoaded || this.data.categoriesLoading || !category) {
      this.showFailure('submitError', 0, '', '请加载并选择作品分类')
      return
    }
    const header = { ...app.getAuthHeader(), 'content-type': 'application/json' }
    this.setData({ submitting: true, submitError: '' })
    // 公共 request 不返回 HTTP 状态，本页直接校验 201，避免将上传或 200 当成发布成功。
    wx.request({
      url: `${app.globalData.baseUrl}/pwork`, method: 'POST', header,
      data: { title, desc, imageUrl, categoryId: category.pcid, exif },
      success: res => {
        if (!this.isCurrentSession(header.Authorization)) return
        const body = res.data
        if (res.statusCode !== 201 || !this.isOkEnvelope(body) || !body.data ||
            typeof body.data.pid !== 'string' || !body.data.pid || body.data.status !== '待审核') {
          this.showFailure('submitError', res.statusCode, body && body.code, '提交失败或响应异常，请核对后重试')
          return
        }
        this.resetForm()
        // 清表单后保持锁至跳转结束，拦住同一轮连点事件。
        this.setData({ submitting: true })
        wx.switchTab({
          url: '/pages/mine/mine',
          success: () => wx.showToast({ title: '已提交，等待审核', icon: 'none', duration: 2500 }),
          fail: () => wx.showToast({ title: '已提交，等待审核；请打开“我的”查看', icon: 'none' }),
          complete: () => this.setData({ submitting: false })
        })
      },
      fail: () => {
        if (this.isCurrentSession(header.Authorization)) {
          this.showFailure('submitError', 0, '', '网络异常，未确认提交结果，请先到“我的”核对')
        }
      },
      complete: () => {
        if (this.isCurrentSession(header.Authorization) && this.data.imageUrl) this.setData({ submitting: false })
      }
    })
  },

  /** 统一成功信封必须同时具有字符串 code/msg 和 data；HTTP 另行校验。 */
  isOkEnvelope(body) {
    return !!body && body.code === 'ok' && typeof body.msg === 'string' &&
      Object.prototype.hasOwnProperty.call(body, 'data')
  },

  /** 将 HTTP/业务失败转为中文提示；401 清凭证并保留发布页登录回跳。 */
  showFailure(field, status, code, fallback = '操作失败，请重试') {
    const messages = {
      400: '参数不合法，请检查图片和表单内容',
      401: '未登录或登录已过期，请重新登录',
      413: '图片过大，不能超过10MB，请重新选择',
      422: '内容未通过安全检查',
      503: '服务暂不可用，请稍后重试'
    }
    const message = messages[status] || (code === 'content_rejected' ? messages[422] : fallback)
    this.setData({ [field]: message })
    wx.showToast({ title: message, icon: 'none' })
    if (status === 401) {
      app.clearLoginInfo(true)
      app.requireLogin(PAGE_URL)
    }
  },

  /** 清除一次发布的全部草稿，不触动已读取的分类字典。 */
  resetForm() {
    this.setData({
      title: '', desc: '', titleLength: 0, descLength: 0,
      previewUrl: '', imageUrl: '', exif: null, exifItems: [],
      categoryIndex: -1, categoryName: '', choosing: false, uploading: false, submitting: false,
      uploadError: '', submitError: ''
    })
  }
})
