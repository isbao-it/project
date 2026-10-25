package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.entity.AjaxResult;
import com.pg.myutils.JwtUtils;
import com.pg.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卡02｜方法级游客白名单的独立 MVC 鉴权适配器测试（T02-21～T02-24、T02-36～T02-48）。
 *
 * 口径：使用真实 AuthInterceptor 与真实 JwtUtils，只在测试 MVC 切片注册匹配冻结签名的探针处理器；
 * 探针按冻结投影响应并记录是否被调用，不作为未来卡（03/05/06/07/08/09）的业务验收。
 * 本切片不加载数据源，不连接数据库，也不触碰任何真实 Controller/Service/Mapper。
 */
@WebMvcTest(controllers = {WhitelistTest.ProbeController.class, WhitelistTest.ProbePageController.class})
@ActiveProfiles("test")
// 说明：@WebMvcTest(controllers=...) 的 TypeExcludeFilter 会把测试源集里的类过滤掉，
// 导致嵌套探针控制器不注册 → 全 404。用 @Import 显式注册（探针类在测试源集内，必须这样绕）。
@Import({JwtUtils.class, WhitelistTest.ProbeController.class, WhitelistTest.ProbePageController.class})
class WhitelistTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JwtUtils jwtUtils;

    @MockBean
    private UserService userService;

    /** 每个用例开始前复位探针调用记录与参数记录。 */
    @BeforeEach
    void resetProbeCounters() {
        ProbeController.resetAll();
        ProbePageController.resetAll();
    }

    // ------------------------------------------------------------------
    // 固定路由不被动态路径游客规则吞掉（T02-21～T02-24）
    // ------------------------------------------------------------------

    /** T02-21：匿名 GET /user/info——401，固定路由不被动态 uid 规则吞掉，处理器不进入。 */
    @Test
    void wx_21_guestUserInfoIsNotSwallowedByDynamicUidRule() throws Exception {
        MvcResult result = mvc.perform(get("/user/info"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.USER_INFO.get()).isZero();
    }

    /** T02-22：匿名 GET /user/{uid}——401，处理器不进入。 */
    @Test
    void wx_22_guestUserProfileIsNotSwallowedByDynamicUidRule() throws Exception {
        MvcResult result = mvc.perform(get("/user/U"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.USER_DETAIL.get()).isZero();
    }

    /** T02-23：匿名 GET /pwork/mine——401，固定路由不被动态 pid 规则吞掉，处理器不进入。 */
    @Test
    void wx_23_guestPworkMineIsNotSwallowedByDynamicPidRule() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/mine"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.PWORK_MINE.get()).isZero();
    }

    /** T02-24：匿名 GET /pwork/following——401，固定路由不被动态 pid 规则吞掉，处理器不进入。 */
    @Test
    void wx_24_guestPworkFollowingIsNotSwallowedByDynamicPidRule() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/following"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.PWORK_FOLLOWING.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 写操作保护（T02-37～T02-40）
    // ------------------------------------------------------------------

    /** T02-37：匿名 POST /comment——401，处理器绝不执行。 */
    @Test
    void wx_37_guestCommentWriteIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pid\":\"P1\",\"content\":\"好图\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.COMMENT_WRITE.get()).isZero();
    }

    /** T02-38：匿名 POST /like/{pid}——401，处理器绝不执行。 */
    @Test
    void wx_38_guestLikeWriteIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/like/P1"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.LIKE_WRITE.get()).isZero();
    }

    /** T02-39：匿名 POST /favorite/{pid}——401，处理器绝不执行。 */
    @Test
    void wx_39_guestFavoriteWriteIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/favorite/P1"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.FAVORITE_WRITE.get()).isZero();
    }

    /** T02-40：匿名 POST /follow/{uid}——401，处理器绝不执行。 */
    @Test
    void wx_40_guestFollowWriteIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/follow/O"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.FOLLOW_WRITE.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 匿名读放行适配器（T02-36、T02-41～T02-46）
    // ------------------------------------------------------------------

    /** T02-36：匿名 GET /pwork/list?page=1&amp;size=10——200，冻结 Page&lt;Work&gt; 形状，处理器被调用。 */
    @Test
    void wx_36_guestPworkListPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/list").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.list[0].pid").value("P1"))
                .andExpect(jsonPath("$.data.list[0].uid").value("U1"))
                .andExpect(jsonPath("$.data.list[0].wtitle").value("雪山"))
                .andExpect(jsonPath("$.data.list[0].wdesc").value("山顶积雪"))
                .andExpect(jsonPath("$.data.list[0].vurl").value("/static/images/1.jpg"))
                .andExpect(jsonPath("$.data.list[0].viewcount").value(12))
                .andExpect(jsonPath("$.data.list[0].votecount").value(3))
                .andExpect(jsonPath("$.data.list[0].wstatus").value("展示"))
                .andExpect(jsonPath("$.data.list[0].cwtime").value("2026-10-21T15:04:05+08:00"))
                .andExpect(jsonPath("$.data.list[0].likep").value(5))
                .andExpect(jsonPath("$.data.list[0].uname").value("作者一"))
                .andExpect(jsonPath("$.data.list[0].avatar").value("/a.png"))
                .andExpect(jsonPath("$.data.list[0].pcname").value("风景"))
                .andExpect(jsonPath("$.data.list[0].realLikeCount").value(7))
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();
        assertOkEnvelope(result);
        assertThat(keysOf(mapper.readTree(result.getResponse().getContentAsString()).get("data")))
                .containsExactlyInAnyOrder("list", "total");
        assertThat(ProbeController.PWORK_LIST.get()).isEqualTo(1);
        assertThat(ProbeController.PWORK_LIST_PAGE).isEqualTo(1);
        assertThat(ProbeController.PWORK_LIST_SIZE).isEqualTo(10);
    }

    /** T02-41：匿名 GET /pwork/P1——200，冻结 work/exif/author 形状，处理器被调用。 */
    @Test
    void wx_41_guestPworkDetailPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.work.pid").value("P1"))
                .andExpect(jsonPath("$.data.work.wstatus").value("展示"))
                .andExpect(jsonPath("$.data.work.viewcount").value(12))
                .andExpect(jsonPath("$.data.exif.focal").value(50.0))
                .andExpect(jsonPath("$.data.exif.aperture").value(1.8))
                .andExpect(jsonPath("$.data.exif.shutter").value("1/250"))
                .andExpect(jsonPath("$.data.exif.iso").value(100))
                .andExpect(jsonPath("$.data.exif.lens").value("50mm"))
                .andExpect(jsonPath("$.data.exif.camera").value("Canon"))
                .andExpect(jsonPath("$.data.author.uid").value("U1"))
                .andExpect(jsonPath("$.data.author.nickname").value("作者一"))
                .andExpect(jsonPath("$.data.author.avatar").value("/a.png"))
                .andReturn();
        assertOkEnvelope(result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("work", "exif", "author");
        assertThat(keysOf(data.get("exif"))).containsExactlyInAnyOrder(
                "focal", "aperture", "shutter", "iso", "lens", "camera");
        assertThat(keysOf(data.get("author"))).containsExactlyInAnyOrder("uid", "nickname", "avatar");
        assertThat(ProbeController.PWORK_DETAIL.get()).isEqualTo(1);
    }

    /** T02-42：匿名 GET /pwork/similar/P1?limit=6——200，data 恰为 {list:[]}，处理器被调用。 */
    @Test
    void wx_42_guestSimilarPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/similar/P1").param("limit", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andReturn();
        assertOkEnvelope(result);
        assertThat(keysOf(mapper.readTree(result.getResponse().getContentAsString()).get("data")))
                .containsExactlyInAnyOrder("list");
        assertThat(ProbeController.PWORK_SIMILAR.get()).isEqualTo(1);
        assertThat(ProbeController.SIMILAR_LIMIT).isEqualTo(6);
    }

    /** T02-43：匿名 GET /comment/list?pid=P1&amp;page=1&amp;size=10——200，{list:[],total:0}，处理器被调用。 */
    @Test
    void wx_43_guestCommentListPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/comment/list")
                        .param("pid", "P1").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andReturn();
        assertOkEnvelope(result);
        assertThat(keysOf(mapper.readTree(result.getResponse().getContentAsString()).get("data")))
                .containsExactlyInAnyOrder("list", "total");
        assertThat(ProbeController.COMMENT_LIST.get()).isEqualTo(1);
        assertThat(ProbeController.COMMENT_LIST_PID).isEqualTo("P1");
    }

    /** T02-44：匿名 GET /category/list——200，list 项为 {pcid,pcname,count}，处理器被调用。 */
    @Test
    void wx_44_guestCategoryListPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.list[0].pcid").value("C1"))
                .andExpect(jsonPath("$.data.list[0].pcname").value("风景"))
                .andExpect(jsonPath("$.data.list[0].count").value(3))
                .andReturn();
        assertOkEnvelope(result);
        assertThat(ProbeController.CATEGORY_LIST.get()).isEqualTo(1);
    }

    /** T02-45：匿名 GET /search?q=山&amp;mode=keyword&amp;page=1&amp;size=10——200，冻结搜索形状，处理器被调用。 */
    @Test
    void wx_45_guestSearchPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/search")
                        .param("q", "山").param("mode", "keyword").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.mode").value("keyword"))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andReturn();
        assertOkEnvelope(result);
        assertThat(keysOf(mapper.readTree(result.getResponse().getContentAsString()).get("data")))
                .containsExactlyInAnyOrder("list", "total", "mode", "degraded");
        assertThat(ProbeController.SEARCH.get()).isEqualTo(1);
        assertThat(ProbeController.SEARCH_Q).isEqualTo("山");
        assertThat(ProbeController.SEARCH_MODE).isEqualTo("keyword");
    }

    /** T02-46：匿名 GET /health——200，{mysql:true,redis:true,ai:true}，处理器被调用。 */
    @Test
    void wx_46_guestHealthPassesAuthAdapter() throws Exception {
        MvcResult result = mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data.mysql").value(true))
                .andExpect(jsonPath("$.data.redis").value(true))
                .andExpect(jsonPath("$.data.ai").value(true))
                .andReturn();
        assertOkEnvelope(result);
        assertThat(keysOf(mapper.readTree(result.getResponse().getContentAsString()).get("data")))
                .containsExactlyInAnyOrder("mysql", "redis", "ai");
        assertThat(ProbeController.HEALTH.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 游客入口带坏凭证与页面 302（T02-47、T02-48）
    // ------------------------------------------------------------------

    /** T02-47：匿名读入口带坏凭证——401，T02-36 的适配器处理器不得被调用。 */
    @Test
    void wx_47_badCredentialOnGuestEntryIsRejected() throws Exception {
        MvcResult result = mvc.perform(get("/pwork/list").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(result, 401, "unauthorized");
        assertThat(ProbeController.PWORK_LIST.get()).isZero();
    }

    /** T02-48：/page/admin/audit 匿名与普通用户两例——302 到 /login/page，无 JSON 信封，页面处理器不进入。 */
    @Test
    void wx_48_adminPageRedirectsToLogin() throws Exception {
        MvcResult anonymous = mvc.perform(get("/page/admin/audit"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login/page"))
                .andReturn();
        assertThat(anonymous.getResponse().getContentAsString()).doesNotContain("\"code\"");
        String normalToken = jwtUtils.generateToken("T02U48", "普通用户");
        MvcResult normal = mvc.perform(get("/page/admin/audit").header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login/page"))
                .andReturn();
        assertThat(normal.getResponse().getContentAsString()).doesNotContain("\"code\"");
        assertThat(ProbePageController.ADMIN_AUDIT.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 探针处理器：仅在测试 MVC 切片注册，不进入生产路径
    // ------------------------------------------------------------------

    /**
     * 鉴权探针控制器：按冻结投影响应并记录是否被调用；
     * 记录被调用只证明游客白名单/写保护生效，不作为未来卡的业务验收。
     */
    @RestController
    static class ProbeController {

        static final AtomicInteger PWORK_LIST = new AtomicInteger();
        static final AtomicInteger PWORK_DETAIL = new AtomicInteger();
        static final AtomicInteger PWORK_SIMILAR = new AtomicInteger();
        static final AtomicInteger COMMENT_LIST = new AtomicInteger();
        static final AtomicInteger CATEGORY_LIST = new AtomicInteger();
        static final AtomicInteger SEARCH = new AtomicInteger();
        static final AtomicInteger HEALTH = new AtomicInteger();
        static final AtomicInteger USER_INFO = new AtomicInteger();
        static final AtomicInteger USER_DETAIL = new AtomicInteger();
        static final AtomicInteger PWORK_MINE = new AtomicInteger();
        static final AtomicInteger PWORK_FOLLOWING = new AtomicInteger();
        static final AtomicInteger COMMENT_WRITE = new AtomicInteger();
        static final AtomicInteger LIKE_WRITE = new AtomicInteger();
        static final AtomicInteger FAVORITE_WRITE = new AtomicInteger();
        static final AtomicInteger FOLLOW_WRITE = new AtomicInteger();

        static volatile int PWORK_LIST_PAGE = -1;
        static volatile int PWORK_LIST_SIZE = -1;
        static volatile int SIMILAR_LIMIT = -1;
        static volatile String COMMENT_LIST_PID = null;
        static volatile String SEARCH_Q = null;
        static volatile String SEARCH_MODE = null;

        /** 复位全部调用与参数记录。 */
        static void resetAll() {
            PWORK_LIST.set(0);
            PWORK_DETAIL.set(0);
            PWORK_SIMILAR.set(0);
            COMMENT_LIST.set(0);
            CATEGORY_LIST.set(0);
            SEARCH.set(0);
            HEALTH.set(0);
            USER_INFO.set(0);
            USER_DETAIL.set(0);
            PWORK_MINE.set(0);
            PWORK_FOLLOWING.set(0);
            COMMENT_WRITE.set(0);
            LIKE_WRITE.set(0);
            FAVORITE_WRITE.set(0);
            FOLLOW_WRITE.set(0);
            PWORK_LIST_PAGE = -1;
            PWORK_LIST_SIZE = -1;
            SIMILAR_LIMIT = -1;
            COMMENT_LIST_PID = null;
            SEARCH_Q = null;
            SEARCH_MODE = null;
        }

        /** GET /pwork/list 探针：记录 page/size 并按冻结 Page&lt;Work&gt; 形状响应。 */
        @GetMapping("/pwork/list")
        public AjaxResult pworkList(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
            PWORK_LIST.incrementAndGet();
            PWORK_LIST_PAGE = page;
            PWORK_LIST_SIZE = size;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", Collections.singletonList(frozenWork()));
            data.put("total", 1);
            return AjaxResult.success(data);
        }

        /** GET /pwork/{pid} 探针：记录调用并按冻结 work/exif/author 形状响应。 */
        @GetMapping("/pwork/{pid}")
        public AjaxResult pworkDetail(@PathVariable String pid) {
            PWORK_DETAIL.incrementAndGet();
            Map<String, Object> exif = new LinkedHashMap<>();
            exif.put("focal", 50.0);
            exif.put("aperture", 1.8);
            exif.put("shutter", "1/250");
            exif.put("iso", 100);
            exif.put("lens", "50mm");
            exif.put("camera", "Canon");
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("uid", "U1");
            author.put("nickname", "作者一");
            author.put("avatar", "/a.png");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("work", frozenWork());
            data.put("exif", exif);
            data.put("author", author);
            return AjaxResult.success(data);
        }

        /** GET /pwork/similar/{pid} 探针：记录 limit 并按冻结形状响应空列表。 */
        @GetMapping("/pwork/similar/{pid}")
        public AjaxResult similar(@PathVariable String pid, @RequestParam(defaultValue = "6") int limit) {
            PWORK_SIMILAR.incrementAndGet();
            SIMILAR_LIMIT = limit;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", Collections.emptyList());
            return AjaxResult.success(data);
        }

        /** GET /comment/list 探针：记录 pid 并按冻结形状响应空页。 */
        @GetMapping("/comment/list")
        public AjaxResult commentList(@RequestParam String pid, @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size) {
            COMMENT_LIST.incrementAndGet();
            COMMENT_LIST_PID = pid;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", Collections.emptyList());
            data.put("total", 0);
            return AjaxResult.success(data);
        }

        /** GET /category/list 探针：按冻结 Category 形状响应。 */
        @GetMapping("/category/list")
        public AjaxResult categoryList() {
            CATEGORY_LIST.incrementAndGet();
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("pcid", "C1");
            category.put("pcname", "风景");
            category.put("count", 3);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", Collections.singletonList(category));
            return AjaxResult.success(data);
        }

        /** GET /search 探针：记录 q/mode 并按冻结搜索形状响应。 */
        @GetMapping("/search")
        public AjaxResult search(@RequestParam String q, @RequestParam(defaultValue = "keyword") String mode,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size) {
            SEARCH.incrementAndGet();
            SEARCH_Q = q;
            SEARCH_MODE = mode;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", Collections.emptyList());
            data.put("total", 0);
            data.put("mode", mode);
            data.put("degraded", false);
            return AjaxResult.success(data);
        }

        /** GET /health 探针：按冻结健康形状响应。 */
        @GetMapping("/health")
        public AjaxResult health() {
            HEALTH.incrementAndGet();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mysql", true);
            data.put("redis", true);
            data.put("ai", true);
            return AjaxResult.success(data);
        }

        /** GET /user/info 探针：记录调用；匿名请求下不应进入。 */
        @GetMapping("/user/info")
        public AjaxResult userInfo() {
            USER_INFO.incrementAndGet();
            return AjaxResult.success();
        }

        /** GET /user/{uid} 探针：记录调用；匿名请求下不应进入。 */
        @GetMapping("/user/{uid}")
        public AjaxResult userDetail(@PathVariable String uid) {
            USER_DETAIL.incrementAndGet();
            return AjaxResult.success();
        }

        /** GET /pwork/mine 探针：记录调用；匿名请求下不应进入。 */
        @GetMapping("/pwork/mine")
        public AjaxResult pworkMine() {
            PWORK_MINE.incrementAndGet();
            return AjaxResult.success();
        }

        /** GET /pwork/following 探针：记录调用；匿名请求下不应进入。 */
        @GetMapping("/pwork/following")
        public AjaxResult pworkFollowing() {
            PWORK_FOLLOWING.incrementAndGet();
            return AjaxResult.success();
        }

        /** POST /comment 探针：记录调用；匿名写请求下绝不进入。 */
        @PostMapping("/comment")
        public AjaxResult commentWrite(@RequestBody Map<String, Object> body) {
            COMMENT_WRITE.incrementAndGet();
            return AjaxResult.success();
        }

        /** POST /like/{pid} 探针：记录调用；匿名写请求下绝不进入。 */
        @PostMapping("/like/{pid}")
        public AjaxResult likeWrite(@PathVariable String pid) {
            LIKE_WRITE.incrementAndGet();
            return AjaxResult.success();
        }

        /** POST /favorite/{pid} 探针：记录调用；匿名写请求下绝不进入。 */
        @PostMapping("/favorite/{pid}")
        public AjaxResult favoriteWrite(@PathVariable String pid) {
            FAVORITE_WRITE.incrementAndGet();
            return AjaxResult.success();
        }

        /** POST /follow/{uid} 探针：记录调用；匿名写请求下绝不进入。 */
        @PostMapping("/follow/{uid}")
        public AjaxResult followWrite(@PathVariable String uid) {
            FOLLOW_WRITE.incrementAndGet();
            return AjaxResult.success();
        }

        /** @return 冻结 Work 形状的静态样本。 */
        private static Map<String, Object> frozenWork() {
            Map<String, Object> work = new LinkedHashMap<>();
            work.put("pid", "P1");
            work.put("uid", "U1");
            work.put("wtitle", "雪山");
            work.put("wdesc", "山顶积雪");
            work.put("vurl", "/static/images/1.jpg");
            work.put("viewcount", 12);
            work.put("votecount", 3);
            work.put("wstatus", "展示");
            work.put("cwtime", "2026-10-21T15:04:05+08:00");
            work.put("likep", 5);
            work.put("uname", "作者一");
            work.put("avatar", "/a.png");
            work.put("pcname", "风景");
            work.put("realLikeCount", 7);
            return work;
        }
    }

    /** 页面探针：仅声明 /page/admin/audit 路由，不实现 04 后台；未授权请求由拦截器 302。 */
    @Controller
    static class ProbePageController {

        static final AtomicInteger ADMIN_AUDIT = new AtomicInteger();

        /** 复位页面探针调用记录。 */
        static void resetAll() {
            ADMIN_AUDIT.set(0);
        }

        /** GET /page/admin/audit 页面路由。 */
        @GetMapping("/page/admin/audit")
        public String adminAuditPage() {
            ADMIN_AUDIT.incrementAndGet();
            return "admin/audit";
        }
    }

    // ------------------------------------------------------------------
    // 信封断言辅助
    // ------------------------------------------------------------------

    /** @return JSON 对象的一级键集合。 */
    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 断言成功信封：200、application/json、恰三键、code=ok、msg 为安全中文、data 非空。 */
    private void assertOkEnvelope(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains("application/json");
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json)).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(json.get("data").isNull()).isFalse();
    }

    /** 断言错误信封：HTTP 状态、application/json、恰三键、code、安全中文 msg、data=null。 */
    private void assertErrorEnvelope(MvcResult result, int httpStatus, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(httpStatus);
        assertThat(result.getResponse().getContentType()).contains("application/json");
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json)).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(json.get("data").isNull()).isTrue();
    }
}
