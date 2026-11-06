/** 微信临时 code 换取凭证；资料加载成功后才离开登录页。 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: { submitting: false },

  /** 重开时用已有凭证读取本人资料，再回目标页面。 */
  onLoad() {
    app.globalData.loginRedirecting = true
    if (app.checkLogin()) {
      this.setData({ submitting: true })
      this.loadProfileAndReturn()
    }
  },

  /** 释放登录引导锁，后续受保护入口可再次引导。 */
  onUnload() {
    app.globalData.loginRedirecting = false
  },

  /** 每次尝试只取一次微信 code、只提交一次，失败后重新取码。 */
  doLogin() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    wx.login({
      timeout: 10000, // 最多等 10 秒，失败后由用户主动重试。
      success: result => {
        if (!result.code) {
          this.loginFailed('微信登录失败，请重试')
          return
        }
        // 输入只有微信返回的临时 code，不拼装身份或回退账号密码。
        // 调试器里 wx.login 给的是模拟 code，必须加 mock: 前缀后端才认（见 app.js useMockLogin）
        const code = app.globalData.useMockLogin ? 'mock:' + (result.code || 'devtools') : result.code
        request('/auth/wxlogin', 'POST', { code })
          .then(res => {
            app.setLoginInfo(res.data)
            return this.loadProfileAndReturn()
          })
          .catch(error => {
            // 封禁不是"登录失败"：直接引导到申诉页，避免用户反复点重试。
            if (error && error.code === 'account_disabled') {
              this.setData({ submitting: false })
              wx.navigateTo({ url: '/pages/appeal/appeal' })
              return
            }
            this.loginFailed(error instanceof Error ? '登录失败，请重试' : undefined)
          })
      },
      fail: error => {
        const timeout = (error.errMsg || '').includes('timeout')
        this.loginFailed(timeout ? '服务不可用，请稍后重试' : '微信登录失败，请重试')
      }
    })
  },

  /** 加载并缓存本人资料，失败时清掉半完成的登录态。 */
  loadProfileAndReturn() {
    return request('/user/info', 'GET')
      .then(res => {
        app.setUserInfo(res.data)
        this.returnToTarget()
      })
      .catch(() => this.loginFailed())
  },

  /** 恢复按钮、清凭证并保留目标；message 为空时由请求层提示。 */
  loginFailed(message) {
    app.clearLoginInfo(true)
    this.setData({ submitting: false })
    if (message) wx.showToast({ title: message, icon: 'none' })
  },

  /** 区分 tab 与普通页回跳，普通页保留原查询参数。 */
  returnToTarget() {
    const target = app.globalData.loginTarget || '/pages/workGallery/workGallery'
    const tabs = ['/pages/workGallery/workGallery', '/pages/userList/userList',
      '/pages/workList/workList', '/pages/mine/mine']
    const tabPath = target.split('?')[0]
    const isTab = tabs.includes(tabPath)
    const navigate = isTab ? wx.switchTab : wx.redirectTo
    navigate({
      url: isTab ? tabPath : target,
      success: () => { app.globalData.loginTarget = '' },
      fail: () => {
        this.setData({ submitting: false })
        wx.showToast({ title: '页面打开失败，请重试', icon: 'none' })
      }
    })
  },

  /** 游客只去公开首页，不触发 wx.login 或换取凭证。 */
  guestBrowse() {
    if (this.data.submitting) return
    app.globalData.loginTarget = ''
    wx.switchTab({ url: '/pages/workGallery/workGallery' })
  }
})
