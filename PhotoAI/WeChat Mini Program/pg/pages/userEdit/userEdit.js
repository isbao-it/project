/** 仅编辑本人的昵称与简介；头像展示只读。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    form: { nickname: '', bio: '' },
    avatarPreviewUrl: '', loading: true, loadError: false, submitting: false
  },

  /** 入口带 uid 也不使用，只按当前凭证读取本人资料。 */
  onLoad() {
    if (app.requireLogin('/pages/userEdit/userEdit')) this.loadUserInfo()
  },

  /** 加载本人资料，成功前禁用保存，避免空表单写回服务器。 */
  loadUserInfo() {
    if (!app.requireLogin('/pages/userEdit/userEdit')) return
    this.setData({ loading: true, loadError: false })
    return request('/user/info', 'GET')
      .then(res => {
        app.setUserInfo(res.data)
        const user = app.globalData.userInfo
        this.setData({
          form: { nickname: user.nickname, bio: user.bio },
          avatarPreviewUrl: this.getFullAvatarUrl(user.avatar)
        })
      })
      .catch(() => this.setData({ loadError: true }))
      .finally(() => this.setData({ loading: false }))
  },

  /** 拼接只读头像地址，没有头像时使用已有缺省图。 */
  getFullAvatarUrl(path) {
    if (!path) return '/images/default.jpg'
    if (/^https?:\/\//.test(path)) return path
    return `${app.globalData.baseUrl}${path.startsWith('/') ? path : '/' + path}`
  },

  /** 输入只接受白名单字段，不允许通过事件写入身份或角色。 */
  onInputChange(e) {
    const { field } = e.currentTarget.dataset
    if (field !== 'nickname' && field !== 'bio') return
    this.setData({ [`form.${field}`]: e.detail.value })
  },

  /** 选择头像（从相册或相机） */
  chooseAvatar() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempFilePath = res.tempFilePaths[0]
        this.uploadAvatar(tempFilePath)
      }
    })
  },

  /**
   * 上传头像到服务器
   * @param {string} filePath - 临时文件路径
   */
  uploadAvatar(filePath) {
    wx.showLoading({ title: '上传中...', mask: true })
    wx.uploadFile({
      url: `${app.globalData.baseUrl}/upload/avatar`,
      filePath: filePath,
      name: 'file',
      success: (res) => {
        wx.hideLoading()
        const data = JSON.parse(res.data)
        if (data.code === 'ok') {
          const avatarUrl = data.data  // 后端返回相对路径，如 /static/images/avatar/xxx.jpg
          this.setData({
            'form.avatar': avatarUrl,
            avatarPreviewUrl: this.getFullAvatarUrl(avatarUrl)
          })
          wx.showToast({ title: '上传成功', icon: 'success' })
        } else {
          wx.showToast({ title: data.msg || '上传失败', icon: 'none' })
        }
      },
      fail: () => {
        wx.hideLoading()
        wx.showToast({ title: '上传失败，请重试', icon: 'none' })
      }
    })
  },

  /** 校验后只 PUT nickname/bio，缓存服务端资料并返回。 */
  submitForm() {
    if (this.data.submitting || this.data.loading || this.data.loadError) return
    if (!app.requireLogin('/pages/userEdit/userEdit')) return
    const { nickname, bio } = this.data.form
    // 冻结 v1.1 按 Unicode 字符数限制 50/255；trim 只判空，不改正文。
    if (!nickname.trim() || Array.from(nickname).length > 50) {
      wx.showToast({ title: '昵称须为1至50个字符且非空白', icon: 'none' })
      return
    }
    if (Array.from(bio).length > 255) {
      wx.showToast({ title: '简介不能超过255个字符', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    // 输出显式投影两个字段，绝不提交整个 form 或旧资料对象。
    request('/user/profile', 'PUT', { nickname, bio })
      .then(res => {
        app.setUserInfo(res.data)
        wx.showToast({ title: '保存成功', icon: 'success' })
        wx.navigateBack({
          fail: () => wx.switchTab({
            url: '/pages/mine/mine',
            fail: () => { this.setData({ submitting: false }) }
          })
        })
      })
      .catch(() => this.setData({ submitting: false }))
  }
})
