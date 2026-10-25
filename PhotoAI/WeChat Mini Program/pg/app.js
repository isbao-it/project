App({
  globalData: {
    // 请替换为您的后端服务器地址（开发时可开启"不校验合法域名"）
    baseUrl: 'http://localhost:8082',
    // 开发者工具/游客模式下 wx.login 只返回模拟 code，无法换真 token；
    // 这里显式开启 mock 登录（后端需 dev profile + WX_MOCK_ENABLED=true）。真机用真实 AppID 时改成 false。
    useMockLogin: true,
    // 登录态信息
    token: null,
    role: null,
    uname: null,
    uid: null,
    isLoggedIn: false,
    userInfo: null,
    loginTarget: '',
    loginRedirecting: false
  },

  onLaunch() {
    console.log('小程序启动')
    // 从本地存储恢复登录态
    this.restoreLoginState()
  },

  /**
   * 从 wx.storage 恢复登录态
   */
  restoreLoginState() {
    try {
      const token = wx.getStorageSync('token')
      const uid = wx.getStorageSync('uid')
      const user = wx.getStorageSync('userInfo')
      if (typeof token === 'string' && token && typeof uid === 'string' && uid) {
        this.setLoginInfo({ token, uid })
        if (user && user.uid === uid) this.setUserInfo(user)
      } else {
        this.clearLoginInfo()
      }
    } catch (e) {
      this.clearLoginInfo()
    }
  },

  /**
   * 检查当前是否已登录
   * @returns {boolean}
   */
  checkLogin() {
    return this.globalData.isLoggedIn && !!this.globalData.token
  },

  /**
   * 存储登录信息到 globalData 和本地缓存
   * @param {Object} data - 微信登录的 token、字符串 uid；不依赖 role/uname。
   */
  setLoginInfo(data) {
    if (!data || typeof data.token !== 'string' || !data.token ||
        typeof data.uid !== 'string' || !data.uid) {
      throw new Error('登录响应缺少凭证')
    }
    this.globalData.token = data.token
    this.globalData.role = null
    this.globalData.uname = null
    this.globalData.userInfo = null
    this.globalData.uid = data.uid
    this.globalData.isLoggedIn = true

    // 同步写入本地存储（持久化）
    wx.setStorageSync('token', data.token)
    wx.setStorageSync('uid', data.uid)
    wx.removeStorageSync('role')
    wx.removeStorageSync('uname')
    wx.removeStorageSync('userInfo')
  },

  /**
   * 清除凭证与本人资料；401 时传 true 保留回跳目标，主动退出时清除。
   * @param {boolean} preserveTarget - 是否保留登录回跳目标。
   */
  clearLoginInfo(preserveTarget = false) {
    this.globalData.token = null
    this.globalData.role = null
    this.globalData.uname = null
    this.globalData.uid = null
    this.globalData.isLoggedIn = false
    this.globalData.userInfo = null
    if (!preserveTarget) this.globalData.loginTarget = ''

    // 清除本地存储
    wx.removeStorageSync('token')
    wx.removeStorageSync('role')
    wx.removeStorageSync('uname')
    wx.removeStorageSync('uid')
    wx.removeStorageSync('userInfo')
  },

  /**
   * 缓存本人资料的展示字段，兼容旧 uname，不缓存手机号等兼容字段。
   * @param {Object} user - /user/info 或 /user/profile 返回的本人资料。
   */
  setUserInfo(user) {
    const profile = {
      uid: user.uid,
      nickname: user.nickname == null ? (user.uname || '') : user.nickname,
      avatar: user.avatar || '',
      bio: user.bio || '',
      role: user.role
    }
    this.globalData.userInfo = profile
    this.globalData.role = profile.role
    this.globalData.uname = profile.nickname
    wx.setStorageSync('userInfo', profile)
  },

  /**
   * 返回当前页面路径及查询参数，供失效登录后回到原位置。
   * @returns {string} 带前导斜杠的页面地址。
   */
  getCurrentPageUrl() {
    const pages = getCurrentPages()
    const page = pages[pages.length - 1]
    if (!page) return '/pages/workGallery/workGallery'
    const query = Object.keys(page.options || {}).map(key =>
      `${encodeURIComponent(key)}=${encodeURIComponent(page.options[key])}`).join('&')
    return '/' + page.route + (query ? '?' + query : '')
  },

  /**
   * 登录守卫：未登录时记住目标并只引导一次。
   * @param {string} [target] - 登录成功后返回的页面，省略时取当前页。
   * @returns {boolean} 是否已有凭证；凭证有效性由后续请求检查。
   */
  requireLogin(target) {
    if (this.checkLogin()) return true
    const current = this.getCurrentPageUrl()
    if (current.split('?')[0] === '/pages/login/login') return false
    if (this.globalData.loginRedirecting) return false
    this.globalData.loginTarget = target || current
    this.globalData.loginRedirecting = true
    wx.reLaunch({
      url: '/pages/login/login',
      fail: () => { this.globalData.loginRedirecting = false }
    })
    return false
  },

  /**
   * 获取请求认证头
   * @returns {Object} 包含 Authorization 字段的对象
   */
  getAuthHeader() {
    if (this.globalData.token) {
      return { 'Authorization': 'Bearer ' + this.globalData.token }
    }
    return {}
  }
})
