/**
 * utils/request.js
 * 微信小程序网络请求封装模块
 * 功能：统一发起 wx.request 请求，自动拼接 baseUrl，携带 JWT token，
 *       处理加载提示、错误提示和业务状态码
 */

const app = getApp() // 获取小程序实例，用于访问全局配置（如 baseUrl、token）

/**
 * 发起网络请求
 * @param {string} url        - 接口路径（相对路径，如 '/user/info'）
 * @param {string} [method='GET'] - HTTP 方法（GET, POST, PUT, DELETE 等）
 * @param {object|null} [data=null] - 请求参数（对象格式，会自动转为 JSON）
 * @returns {Promise}         - 成功保留完整 code/msg/data 信封，失败拒绝原响应体。
 */
const request = (url, method = 'GET', data = null) => {
  // 返回 Promise，以便外部使用 async/await 或 .then/.catch
  return new Promise((resolve, reject) => {
    wx.showLoading({ title: '加载中...', mask: true })

    // 构建请求头：携带 JWT token
    const header = {
      'content-type': 'application/json'
    }
    // 微信临时 code 独立换取凭证，不能夹带旧会话。
    const authHeader = app.getAuthHeader()
    if (url !== '/auth/wxlogin' && authHeader.Authorization) {
      header.Authorization = authHeader.Authorization
    }

    // 发起微信小程序原生请求
    wx.request({
      // 拼接完整 URL：全局 baseUrl + 传入的路径
      url: `${app.globalData.baseUrl}${url}`,
      method: method,                // 请求方法
      data: data,                    // 请求参数
      header: header,
      // wx.request 的 success 也会收到 4xx/5xx，必须检查 HTTP 和业务码。
      success: (res) => {
        wx.hideLoading()            // 隐藏加载提示
        const body = res.data || {}

        // 退出或换账号后，旧请求不能清掉新凭证，也不能回填旧用户资料。
        if (header.Authorization && header.Authorization !== app.getAuthHeader().Authorization) {
          reject(body)
          return
        }

        // 处理 401 未授权：token 过期或无效
        if (res.statusCode === 401) {
          app.clearLoginInfo(true)
          if (url === '/auth/wxlogin') {
            wx.showToast({ title: '登录失败，请重试', icon: 'none' })
          } else {
            app.requireLogin()
          }
          reject(body)
          return
        }

        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 'ok') {
          resolve(res.data)          // 成功：将数据返回给调用方
        } else {
          // 业务失败：显示后端返回的错误信息，若没有则显示默认文案
          wx.showToast({
            title: res.statusCode === 503 ? '服务不可用，请稍后重试' : (body.msg || '请求失败'),
            icon: 'none'
          })
          reject(res.data)           // 拒绝 Promise，传递错误数据
        }
      },
      // 请求失败时的回调（网络不通、超时、域名未配置等）
      fail: (err) => {
        wx.hideLoading()            // 隐藏加载提示
        wx.showToast({
          title: '网络错误，请稍后重试',
          icon: 'error'
        })
        reject(err)                 // 拒绝 Promise，传递错误对象
      }
    })
  })
}

// 导出 request 方法，供其他模块使用
module.exports = { request }
