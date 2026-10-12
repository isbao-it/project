package com.pg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.User;
import com.pg.myutils.JwtUtils;
import com.pg.service.UserService;
import com.pg.entity.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class userController {
//依赖注入与组件初始化
    @Autowired
    private UserService userService;//使用 BCrypt 算法对密码进行单向加密，保证数据库泄露时用户密码的安全。
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @Autowired
    private JwtUtils jwtUtils;    // 注入 JwtUtils

    // ------------------- 分页条件查询 -------------------
    //将 HTTP GET 请求映射到特定的方法上
    @GetMapping("/list")
    /**
     * 获取用户列表
     * @param pageNum 页码数，默认值为1
     * @param pageSize 每页条数，默认值为10
     * @param user 用户实体，用于接收查询条件
     * @return AjaxResult 包含分页信息和用户列表
     */
    public AjaxResult userList(
            @RequestParam(defaultValue = "1") Integer pageNum,    // 页码参数，默认值为1
            @RequestParam(defaultValue = "10") Integer pageSize,   // 每页条数参数，默认值为10
            User user) {   // 直接用实体接收条件，前端参数名与实体字段名一致
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
        // 返回成功结果，包含总记录数和用户列表
        return AjaxResult.success(pageInfo.getTotal(), pageInfo.getList());
    }


    // ------------------- 新增用户 -------------------
    //处理HTTP POST请求，用于添加资源
    @PostMapping("/add")
    public AjaxResult addUser(@RequestBody User user) {
        try {
            // 必填校验
            if (user.getPnum() == null || user.getPnum().trim().isEmpty())
                return AjaxResult.error(400, "手机号不能为空");
            if (user.getPwd() == null || user.getPwd().trim().isEmpty())
                return AjaxResult.error(400, "密码不能为空");
            if (user.getUname() == null || user.getUname().trim().isEmpty())
                return AjaxResult.error(400, "用户名不能为空");

            // 手机号唯一
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getPnum, user.getPnum());
            if (userService.getOne(wrapper) != null)
                return AjaxResult.error(400, "手机号已被注册");

            // 用户名唯一
            wrapper.clear();
            wrapper.eq(User::getUname, user.getUname());
            if (userService.getOne(wrapper) != null)
                return AjaxResult.error(400, "用户名已存在");

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
                        return AjaxResult.error(400, "积分必须在 0 到 9999 之间");
                    }
                    user.setVote(String.valueOf(voteValue));
                } catch (NumberFormatException e) {
                    return AjaxResult.error(400, "积分必须为整数");
                }
            }

            if (user.getAvatar() == null || user.getAvatar().isEmpty())
                user.setAvatar("/static/images/avatar/admin.jpg");

            // 密码加密
            user.setPwd(passwordEncoder.encode(user.getPwd()));

            // 调用userService的save方法，将user对象保存到数据库
            // 保存成功后，success变量会保存返回值，通常表示操作是否成功
            boolean success = userService.save(user);
            return success ? AjaxResult.success("添加成功") : AjaxResult.error(500, "添加失败");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "服务器内部错误：" + e.getMessage());
        }
    }

    // ------------------- 更新用户 -------------------
    //与POST请求不同，PUT通常用于更新已存在的资源，而不是创建新资源
    @PutMapping("/update")
    public AjaxResult updateUser(@RequestBody User user, HttpServletRequest request) {
        // 获取当前登录用户信息
        String currentUid = (String) request.getAttribute("uid");
        String currentRole = (String) request.getAttribute("role");
        if (currentUid == null) {
            return AjaxResult.error(401, "未登录");
        }
        // 普通用户只能修改自己的资料
        if ("普通用户".equals(currentRole) && !currentUid.equals(user.getUid())) {
            return AjaxResult.error(403, "无权修改他人信息");
        }

        try {
            if (user.getUid() == null || user.getUid().trim().isEmpty())
                return AjaxResult.error(400, "用户ID不能为空");

            User existing = userService.getById(user.getUid());
            if (existing == null)
                return AjaxResult.error(404, "用户不存在");

            // 手机号唯一性（若修改且新号被其他用户占用）
            if (user.getPnum() != null && !user.getPnum().trim().isEmpty()) {
                String newPnum = user.getPnum().trim();
                if (!newPnum.equals(existing.getPnum())) {
                    LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(User::getPnum, newPnum);
                    User other = userService.getOne(wrapper);
                    if (other != null && !other.getUid().equals(user.getUid()))
                        return AjaxResult.error(400, "手机号已被其他用户使用");
                    existing.setPnum(newPnum);
                }
            }

            // 更新非空字段
            if (user.getUname() != null && !user.getUname().trim().isEmpty())
                existing.setUname(user.getUname());
            if (user.getPwd() != null && !user.getPwd().isEmpty())
                existing.setPwd(passwordEncoder.encode(user.getPwd()));
            if (user.getStatus() != null && !user.getStatus().isEmpty())
                existing.setStatus(user.getStatus());
            if (user.getRole() != null && !user.getRole().isEmpty())
                existing.setRole(user.getRole());

            // 积分范围校验（更新部分）
            if (user.getVote() != null && !user.getVote().isEmpty()) {
                try {
                    int voteValue = Integer.parseInt(user.getVote());
                    if (voteValue < 0 || voteValue > 9999) {
                        return AjaxResult.error(400, "积分必须在 0 到 9999 之间");
                    }
                    existing.setVote(String.valueOf(voteValue));
                } catch (NumberFormatException e) {
                    return AjaxResult.error(400, "积分必须为整数");
                }
            }

            if (user.getAvatar() != null && !user.getAvatar().isEmpty())
                existing.setAvatar(user.getAvatar());

            boolean success = userService.updateById(existing);
            return success ? AjaxResult.success("更新成功") : AjaxResult.error(500, "更新失败");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "服务器内部错误：" + e.getMessage());
        }
    }

    // ------------------- 禁用用户 -------------------
    @PutMapping("/disable/{uid}")
    public AjaxResult disableUser(@PathVariable String uid) {
        try {
            if (uid == null || uid.trim().isEmpty())
                return AjaxResult.error(400, "用户ID不能为空");
            User user = userService.getById(uid);
            if (user == null)
                return AjaxResult.error(404, "用户不存在");
            user.setStatus("禁用");
            boolean success = userService.updateById(user);
            return success ? AjaxResult.success("已禁用用户") : AjaxResult.error(500, "操作失败");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "服务器内部错误");
        }
    }

    // ------------------- 启用用户 -------------------
    @PutMapping("/enable/{uid}")
    public AjaxResult enableUser(@PathVariable String uid) {
        try {
            if (uid == null || uid.trim().isEmpty())
                return AjaxResult.error(400, "用户ID不能为空");
            User user = userService.getById(uid);
            if (user == null)
                return AjaxResult.error(404, "用户不存在");
            user.setStatus("正常");
            boolean success = userService.updateById(user);
            return success ? AjaxResult.success("已启用用户") : AjaxResult.error(500, "操作失败");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "服务器内部错误");
        }
    }

    // ------------------- 批量物理删除用户 -------------------
    @DeleteMapping("/batch")
    public AjaxResult batchDeleteUser(@RequestBody List<String> uidList) {
        try {
            if (uidList == null || uidList.isEmpty()) {
                return AjaxResult.error(400, "请选择要删除的用户");
            }
            boolean removed = userService.removeByIds(uidList);
            return removed ? AjaxResult.success("批量删除成功") : AjaxResult.error(500, "批量删除失败");
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error(500, "服务器内部错误");
        }
    }

    // ------------------- 根据ID查询（详情）-------------------
    @GetMapping("/{uid}")
    public AjaxResult getUserById(@PathVariable String uid, HttpServletRequest request) {
        String currentUid = (String) request.getAttribute("uid");
        String role = (String) request.getAttribute("role");
        // 普通用户只能查看自己的信息
        if ("普通用户".equals(role) && !currentUid.equals(uid)) {
            return AjaxResult.error(403, "无权查看他人信息");
        }
        User user = userService.getById(uid);
        return user == null ? AjaxResult.error(404, "用户不存在") : AjaxResult.success(user);
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
        // 1. 根据手机号查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPnum, pnum);
        User user = userService.getOne(wrapper);
        if (user == null) {
            return AjaxResult.error(401, "手机号或密码错误");
        }

        // 2. 验证密码（注意：密码使用 BCrypt 加密存储，这里用 matches 比对）
        if (!passwordEncoder.matches(pwd, user.getPwd())) {
            return AjaxResult.error(401, "手机号或密码错误");
        }

        // 3. 检查账号状态
        if ("禁用".equals(user.getStatus())) {
            return AjaxResult.error(403, "账号已被禁用，请联系管理员");
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
            return AjaxResult.error(401, "未登录，请先登录");
        }
        User user = userService.getById(uid);
        if (user == null) {
            return AjaxResult.error(404, "用户不存在");
        }
        user.setPwd(null); // 清除密码字段，防止敏感信息泄露
        // 返回用户信息（密码字段不应返回，但实体中有密码，可在前端忽略或设置 @JsonIgnore）
        return AjaxResult.success(1, user);
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
                System.out.println("已加密用户: " + user.getUname());
            }
        }
    }

}

