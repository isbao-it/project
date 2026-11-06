package com.pg.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.User;
import com.pg.myutils.JwtUtils;
import com.pg.service.UserService;
import com.pg.service.ProfileService;
import com.pg.entity.AjaxResult;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.common.RequiresRole;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
/** 管理用户和本人资料；管理字段与普通用户白名单分支分别控制写库权限。 */
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
//依赖注入与组件初始化
    private final UserService userService;//使用 BCrypt 算法对密码进行单向加密，保证数据库泄露时用户密码的安全。
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtUtils jwtUtils;    // 注入 JwtUtils
    private final ProfileService profileService;
    private final HttpServletRequest currentRequest;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param userService 用户数据服务
     * @param jwtUtils 令牌签发与验签组件
     * @param profileService 冻结账号资料投影服务
     * @param currentRequest 当前请求代理，用于登录表单协议校验
     */
    public UserController(UserService userService, JwtUtils jwtUtils, ProfileService profileService,
                          HttpServletRequest currentRequest) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
        this.profileService = profileService;
        this.currentRequest = currentRequest;
    }

    // ------------------- 分页条件查询 -------------------
    //将 HTTP GET 请求映射到特定的方法上
    @GetMapping("/list")
    @RequiresRole("管理员")
    /**
     * 获取用户列表
     * @param pageNum 页码数，默认值为1
     * @param pageSize 每页条数，默认值为10
     * @param user 用户实体，用于接收查询条件
     * @param request 已验证身份，用于决定手机号的响应可见范围
     * @return AjaxResult 包含分页信息和用户列表
     */
    public AjaxResult userList(
            @RequestParam(defaultValue = "1") Integer pageNum,    // 页码参数，默认值为1
            @RequestParam(defaultValue = "10") Integer pageSize,   // 每页条数参数，默认值为10
            User user, HttpServletRequest request) {   // 查询条件与已验证身份分开传入
        validateEnums(user);
        // 使用PageHelper进行分页
        PageHelper.startPage(pageNum, pageSize);
        // 创建参数Map，用于查询条件
        Map<String, Object> param = new HashMap<>();
        param.put("uname", user.getUname());    // 添加用户名条件
        param.put("pnum", user.getPnum());      // 添加手机号条件
        param.put("role", user.getRole());      // 添加角色条件
        param.put("status", user.getStatus());  // 添加状态条件
        // 查询用户列表
        List<User> list = userService.queryUser(param);
        // 创建分页信息对象
        PageInfo<User> pageInfo = new PageInfo<>(list);
        for (User item : list) {
            item.maskPhoneFor((String) request.getAttribute("role"));
        }
        // 返回成功结果，包含总记录数和用户列表
        return AjaxResult.success(pageInfo.getTotal(), pageInfo.getList());
    }


    // ------------------- 新增用户 -------------------
    //处理HTTP POST请求，用于添加资源
    @PostMapping("/add")
    @RequiresRole("管理员")
    /**
     * 新增用户并加密密码；成功会写库。
     * @param user 新用户字段及明文密码
     * @return 新增结果；非法输入由全局出口返回 400
     */
    public AjaxResult addUser(@RequestBody User user) {
        validateEnums(user);
        try {
            // 必填校验
            if (user.getPnum() == null || user.getPnum().trim().isEmpty())
                throw new BizException(ErrorCode.INVALID_REQUEST, "手机号不能为空");
            if (user.getPwd() == null || user.getPwd().trim().isEmpty())
                throw new BizException(ErrorCode.INVALID_REQUEST, "密码不能为空");
            if (user.getUname() == null || user.getUname().trim().isEmpty())
                throw new BizException(ErrorCode.INVALID_REQUEST, "用户名不能为空");

            // 手机号唯一
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getPnum, user.getPnum());
            if (userService.getOne(wrapper) != null)
                throw new BizException(ErrorCode.CONFLICT, "手机号已被注册");

            // 用户名唯一
            wrapper.clear();
            wrapper.eq(User::getUname, user.getUname());
            if (userService.getOne(wrapper) != null)
                throw new BizException(ErrorCode.CONFLICT, "用户名已存在");

            // 主键由触发器生成
            if (user.getUid() == null || user.getUid().trim().isEmpty())
                user.setUid(null);

            // 默认值
            if (user.getStatus() == null || user.getStatus().isEmpty()) user.setStatus("正常");
            if (user.getRole() == null || user.getRole().isEmpty()) user.setRole("普通用户");

            // 积分范围校验（新增部分）
            if (user.getVote() == null || user.getVote().isEmpty()) {
                user.setVote("0");
            } else {
                try {
                    int voteValue = Integer.parseInt(user.getVote());
                    if (voteValue < 0 || voteValue > 9999) {
                        throw new BizException(ErrorCode.INVALID_REQUEST, "积分必须在 0 到 9999 之间");
                    }
                    user.setVote(String.valueOf(voteValue));
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.INVALID_REQUEST, "积分必须为整数");
                }
            }

            if (user.getAvatar() == null || user.getAvatar().isEmpty())
                user.setAvatar("/static/images/avatar/admin.jpg");

            // 密码加密
            user.setPwd(passwordEncoder.encode(user.getPwd()));

            // 调用userService的save方法，将user对象保存到数据库
            // 保存成功后，success变量会保存返回值，通常表示操作是否成功
            boolean success = userService.save(user);
            if (!success) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "添加失败");
            }
            return AjaxResult.success("添加成功");
        } catch (BizException e) {
            throw e;
        }
    }

    // ------------------- 更新用户 -------------------
    //与POST请求不同，PUT通常用于更新已存在的资源，而不是创建新资源
    @PutMapping("/update")
    @RequiresRole(value = "管理员", allowSelfUpdate = true)
    /**
     * 更新指定用户资料；普通用户只写姓名、头像和手机号，管理员另可写角色、状态和积分。
     * @param user 请求中的目标用户和候选资料字段
     * @param request 拦截器验证后的当前身份
     * @return 更新结果；失败交由全局处理器转换 HTTP 状态
     */
    public AjaxResult updateUser(@RequestBody User user, HttpServletRequest request) {
        // 输入的 uid 只用于定位目标，绝不能作为新的身份覆盖数据库主键。
        String currentUid = (String) request.getAttribute("uid");
        String currentRole = (String) request.getAttribute("role");
        if (currentUid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean administrator = "管理员".equals(currentRole);
        if (!administrator && !"普通用户".equals(currentRole)) {
            throw new BizException(ErrorCode.FORBIDDEN, 403);
        }
        if (!administrator && user.getUid() != null && !currentUid.equals(user.getUid())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String targetUid = administrator ? user.getUid() : currentUid;
        if (targetUid == null || !targetUid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        User existing = userService.getById(targetUid);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // 非法枚举对所有角色都是坏请求；合法的受保护字段仍在普通用户分支静默忽略。
        validateEnums(user);

        // 从空补丁复制白名单，避免客户端新增字段将来被整实体更新意外写入。
        User changes = new User();
        changes.setUid(targetUid);
        if (user.getPnum() != null && !user.getPnum().trim().isEmpty()) {
            String newPnum = user.getPnum().trim();
            if (!newPnum.equals(existing.getPnum())) {
                LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(User::getPnum, newPnum).ne(User::getUid, targetUid);
                if (userService.count(wrapper) > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT);
                }
            }
            changes.setPnum(newPnum);
        }
        if (user.getUname() != null && !user.getUname().trim().isEmpty()) {
            changes.setUname(user.getUname());
        }
        if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            changes.setAvatar(user.getAvatar());
        }

        if (administrator) {
            if (user.getStatus() != null && !user.getStatus().isEmpty())
                changes.setStatus(user.getStatus());
            if (user.getRole() != null && !user.getRole().isEmpty())
                changes.setRole(user.getRole());
            if (user.getVote() != null && !user.getVote().isEmpty()) {
                try {
                    int voteValue = Integer.parseInt(user.getVote());
                    if (voteValue < 0 || voteValue > 9999) { // 保留既有积分范围，越界会影响后续积分业务。
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    }
                    changes.setVote(String.valueOf(voteValue));
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
            }
        }
        // TODO 卡 03：改密走独立接口；此入口对所有角色均忽略 pwd，openid 也不复制。
        boolean hasChanges = changes.getUname() != null || changes.getAvatar() != null
                || changes.getPnum() != null || changes.getRole() != null
                || changes.getStatus() != null || changes.getVote() != null;
        if (hasChanges && !userService.updateById(changes)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // 输出仅含更新结果，不返回用户实体中的敏感字段。
        return AjaxResult.success("更新成功");
    }

    // ------------------- 禁用用户 -------------------
    @PutMapping("/disable/{uid}")
    @RequiresRole("管理员")
    /**
     * 禁用指定账号并写库。
     * @param uid 待禁用账号标识
     * @return 操作结果；缺失账号返回 404
     */
    public AjaxResult disableUser(@PathVariable String uid) {
        try {
            if (uid == null || uid.trim().isEmpty())
                throw new BizException(ErrorCode.INVALID_REQUEST, "用户ID不能为空");
            User user = userService.getById(uid);
            if (user == null)
                throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
            user.setStatus("禁用");
            boolean success = userService.updateById(user);
            if (!success) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "操作失败");
            }
            return AjaxResult.success("已禁用用户");
        } catch (BizException e) {
            throw e;
        }
    }

    // ------------------- 启用用户 -------------------
    @PutMapping("/enable/{uid}")
    @RequiresRole("管理员")
    /**
     * 启用指定账号并写库。
     * @param uid 待启用账号标识
     * @return 操作结果；缺失账号返回 404
     */
    public AjaxResult enableUser(@PathVariable String uid) {
        try {
            if (uid == null || uid.trim().isEmpty())
                throw new BizException(ErrorCode.INVALID_REQUEST, "用户ID不能为空");
            User user = userService.getById(uid);
            if (user == null)
                throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
            user.setStatus("正常");
            boolean success = userService.updateById(user);
            if (!success) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "操作失败");
            }
            return AjaxResult.success("已启用用户");
        } catch (BizException e) {
            throw e;
        }
    }

    // ------------------- 批量物理删除用户 -------------------
    @DeleteMapping("/batch")
    @RequiresRole("管理员")
    /**
     * 批量删除给定账号。
     * @param uidList 待删除账号标识列表
     * @return 删除结果；空列表拒绝写库
     */
    public AjaxResult batchDeleteUser(@RequestBody List<String> uidList) {
        try {
            if (uidList == null || uidList.isEmpty()) {
                throw new BizException(ErrorCode.INVALID_REQUEST, "请选择要删除的用户");
            }
            boolean removed = userService.removeByIds(uidList);
            if (!removed) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "批量删除失败");
            }
            return AjaxResult.success("批量删除成功");
        } catch (BizException e) {
            throw e;
        }
    }

    // ------------------- 根据ID查询（详情）-------------------
    @GetMapping("/{uid}")
    /**
     * 读取指定用户的脱敏主页和统计，不写库。
     * @param uid 最长 64 位的字母、数字、下划线或短横线标识（卡 01-T T3.4）
     * @param request 拦截器验证后的身份
     * @return 用户资料；错误由全局处理器转换
     */
    public AjaxResult getUserById(@PathVariable String uid, HttpServletRequest request) {
        String currentUid = (String) request.getAttribute("uid");
        String role = (String) request.getAttribute("role");
        return AjaxResult.success(userService.home(uid, currentUid, role));
    }

    /**
     * 分页读取目标用户的展示作品，不写库。
     * @param uid 目标用户编号
     * @param viewerUid 拦截器验证的请求者，可为空
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return 与作品广场一致的 list/total 信封
     */
    @GetMapping("/{uid}/works")
    public AjaxResult works(@PathVariable String uid,
                            @RequestAttribute(value = "uid", required = false) String viewerUid,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(userService.works(uid, viewerUid, page, size));
    }

    /**
     * 分页读取可见收藏，不写库；关闭公开且非本人时返回固定的 403 提示。
     * @param uid 目标用户编号
     * @param viewerUid 已验证请求者，可为空
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return list/total 或收藏不可见信封
     */
    @GetMapping("/{uid}/favorites")
    public ResponseEntity<AjaxResult> favorites(@PathVariable String uid,
                            @RequestAttribute(value = "uid", required = false) String viewerUid,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        try {
            return ResponseEntity.ok(AjaxResult.success(userService.favorites(uid, viewerUid, page, size)));
        } catch (BizException exception) {
            if (exception.getCode() != ErrorCode.FORBIDDEN) {
                throw exception;
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AjaxResult.error(ErrorCode.FORBIDDEN).put("msg", "该用户的收藏不可见"));
        }
    }

    /**
     * 分页读取可见的帖子赞，不写库；关闭公开且非本人时返回固定的 403 提示。
     * @param uid 目标用户编号
     * @param viewerUid 已验证请求者，可为空
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return list/total 或喜欢不可见信封
     */
    @GetMapping("/{uid}/likes")
    public ResponseEntity<AjaxResult> likes(@PathVariable String uid,
                            @RequestAttribute(value = "uid", required = false) String viewerUid,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        try {
            return ResponseEntity.ok(AjaxResult.success(userService.likes(uid, viewerUid, page, size)));
        } catch (BizException exception) {
            if (exception.getCode() != ErrorCode.FORBIDDEN) {
                throw exception;
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AjaxResult.error(ErrorCode.FORBIDDEN).put("msg", "该用户的喜欢不可见"));
        }
    }

    /**
     * 分页读取目标用户可见的关注列表，不写库。
     * @param uid 目标用户编号
     * @param viewerUid 拦截器验证的当前登录用户
     * @param page 页码，默认一
     * @param size 页长，默认十，最多五十
     * @return 与本人关注列表一致的四字段 list/total 信封
     */
    @GetMapping("/{uid}/following")
    public AjaxResult following(@PathVariable String uid,
                                @RequestAttribute("uid") String viewerUid,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(userService.following(uid, viewerUid, page, size));
    }

    /**
     * 分页读取目标用户可见的粉丝列表，不写库。
     * @param uid 目标用户编号
     * @param viewerUid 拦截器验证的当前登录用户
     * @param page 页码，默认一
     * @param size 页长，默认十，最多五十
     * @return 与本人粉丝列表一致的四字段 list/total 信封
     */
    @GetMapping("/{uid}/followers")
    public AjaxResult followers(@PathVariable String uid,
                                @RequestAttribute("uid") String viewerUid,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(userService.followers(uid, viewerUid, page, size));
    }

    /**
     * 修改本人可见性；输入身份仅来自拦截器属性，成功会写库。
     * @param uid 已验证的当前登录用户
     * @param body 必须含 visFavorite、visLike、visFollowing、visFollowers 四个布尔字段，禁止携带 uid
     * @return 已保存的四个开关值
     */
    @PutMapping("/privacy")
    public AjaxResult privacy(@RequestAttribute String uid, @RequestBody Map<String, Object> body) {
        return AjaxResult.success(userService.updatePrivacy(uid, body));
    }


    // ======================== 登录接口 ========================
    /**
     * 用户登录
     * @param pnum 手机号
     * @param pwd  密码（明文，后端会加密比对）
     * @return     包含 token 和用户信息的 AjaxResult
     */
    @PostMapping("/login")
    public AjaxResult login(@RequestParam String pnum, @RequestParam String pwd) {
        if (!pnum.matches("[0-9]{11}") || pwd.trim().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "手机号或密码格式不合法");
        }
        // 1. 根据手机号查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPnum, pnum);
        User user = userService.getOne(wrapper);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }

        // 2. 验证密码（注意：密码使用 BCrypt 加密存储，这里用 matches 比对）
        if (user.getPwd() == null || !passwordEncoder.matches(pwd, user.getPwd())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }

        // 3. 检查账号状态：禁用要给明确封禁提示，不能与"密码错误"混为一谈
        if (!"正常".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "账号已被封禁");
        }
        if (!"管理员".equals(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可登录后台");
        }
        // 保留旧错误凭据的 401 优先级；成功签发前严格收口为表单，避免 URL 查询携带密码。
        String contentType = currentRequest.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim()
                .equalsIgnoreCase("application/x-www-form-urlencoded") || currentRequest.getQueryString() != null) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "登录必须使用表单提交");
        }

        // 4. 生成 JWT token
        String token = jwtUtils.generateToken(user.getUid(), user.getRole());

        // 5. 返回 token 及用户基本信息（用于前端页面跳转和展示）
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("role", user.getRole());
        data.put("uname", user.getUname());
        data.put("uid", user.getUid());
        return AjaxResult.success(data);
    }

    // ======================== 获取当前登录用户信息 ========================
    /**
     * 获取当前登录用户的详细信息（用于个人资料页、顶部导航栏等）
     * @param request HttpServletRequest，从拦截器中获取 uid
     * @return 用户对象（不包含敏感信息如密码）
     */
    @GetMapping("/info")
    public AjaxResult getCurrentUser(HttpServletRequest request) {
        // 从拦截器中存入的 request 属性中获取当前用户ID
        String uid = (String) request.getAttribute("uid");
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录，请先登录");
        }
        // 主体状态由拦截器只校验一次；资料通过独立 Mapper 投影，保留兼容字段及旧时间格式。
        return AjaxResult.success(profileService.info(uid));
    }

    private void validateEnums(User user) {
        // 冻结断言 T12.1 要求带前导空格的角色也拒绝，先 trim 再检查规范值及原值一致性。
        if (user.getRole() != null) {
            String role = user.getRole().trim();
            if (!role.equals(user.getRole()) || !("管理员".equals(role) || "普通用户".equals(role))) {
                throw new BizException(ErrorCode.INVALID_REQUEST, 400);
            }
            user.setRole(role);
        }
        if (user.getStatus() != null) {
            String status = user.getStatus().trim();
            if (!status.equals(user.getStatus()) || !("正常".equals(status) || "禁用".equals(status))) {
                throw new BizException(ErrorCode.INVALID_REQUEST, 400);
            }
            user.setStatus(status);
        }
    }

    @PostConstruct
    public void encryptExistingPasswords() {
        List<User> users = userService.list();
        for (User user : users) {
            String pwd = user.getPwd();
            // 如果密码不是以 $2a$ 开头（BCrypt 特征），则加密
            if (pwd != null && !pwd.startsWith("$2a$")) {
                user.setPwd(passwordEncoder.encode(pwd));
                userService.updateById(user);
                log.info("已加密用户: {}", user.getUname());
            }
        }
    }

}

