/**
 * 账号申诉页：被封禁用户在此提交申诉。
 * 免登录：身份靠 wx.login 的临时 code 由后端换 openid 自证（契约 docs/接口增补_ADMIN.md §7.1）。
 */
const { request } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    content: '',
    submitting: false,
    done: false
  },

  /** 记录原文，不做 trim——后端按原文存，校验只看是否全空白。 */
  onInput(event) {
    this.setData({ content: event.detail.value })
  },

  /** 校验长度与空白后取一次 code 提交，失败只恢复按钮不重复提交。 */
  submit() {
    if (this.data.submitting) {
      return
    }
    const content = this.data.content
    if (!content || !content.trim()) {
      wx.showToast({ title: '请填写申诉理由', icon: 'none' })
      return
    }
    if (content.length > 200) {
      wx.showToast({ title: '申诉理由不能超过 200 字', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    wx.login({
      timeout: 10000, // 最多等 10 秒，超时让用户主动重试，避免一直转圈。
      success: result => {
        if (!result.code) {
          this.fail('获取微信身份失败，请重试')
          return
        }
        // 调试器里 wx.login 给的是模拟 code，必须加 mock: 前缀后端才认（与登录页一致）。
        const code = app.globalData.useMockLogin ? 'mock:' + (result.code || 'devtools') : result.code
        request('/appeal', 'POST', { code: code, content: content })
          .then(() => {
            this.setData({ submitting: false, done: true, content: '' })
          })
          .catch(() => {
            // 业务错误的具体文案已由 request 层 toast，这里只恢复按钮。
            this.setData({ submitting: false })
          })
      },
      fail: () => {
        this.fail('微信登录失败，请重试')
      }
    })
  },

  /** 统一失败处理：恢复按钮并提示一句可行动的话。 */
  fail(message) {
    this.setData({ submitting: false })
    wx.showToast({ title: message, icon: 'none' })
  }
})
