package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 卡03 上传与EXIF剥离冻结测试（T03-38..T03-47，测试卡冻结表当前末行）。
 * 只连 db_pg_test_03，夹具全部在本类内生成（含六项EXIF/GPS/机身和镜头序列号的JPEG为类内手工TIFF拼接），
 * 不继承共享基座；T03-40/T03-41 走真实嵌入式HTTP验证 multipart 10MB 边界，其余走 MockMvc；
 * ExifStripper/WorkStorage/MsgSecCheck 属本卡新接口，接口未合入时跳过替身安装、由路由缺失自然红，
 * 合入后通过运行时反射把 WorkUploadController 注入的对应字段替换为抛出故障的代理，用后恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ExifStripTest {

    /** 测试库固定指向 db_pg_test_03，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_03?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
        // 实现只在 dev profile 且开关关闭时跳过真实文本安全服务；本类不测登录，激活 dev 无副作用
        registry.add("wx.msg-sec-check-enabled", () -> "false");
    }

    private static final Logger log = LoggerFactory.getLogger(ExifStripTest.class);

    private static final String U = "es03-u";
    private static final String C1 = "es03-c1";
    private static final String UPLOAD_PATH = "/upload/work";
    private static final String UPLOAD_DIR = "uploads/work";
    private static final String URL_PREFIX = "/uploads/work/";
    private static final long LIMIT_10MB = 10L * 1024L * 1024L;
    private static final int IMAGE_SIZE = 32;
    private static final String BOUNDARY = "----es03-boundary-7d4f";
    private static final String ID_REGEX = "[A-Za-z0-9_-]{1,36}";
    private static final String[] EXIF_KEYS = {"focal", "aperture", "shutter", "iso", "lens", "camera"};
    private static final String BODY_SERIAL = "BODY-SN-2026";
    private static final String LENS_SERIAL = "LENS-SN-98765";

    /** EXIF TIFF 手工布局偏移：ASCII 数据区、两个子IFD及RATIONAL值区，写入后按总长326字节自检。 */
    private static final int EXIF_OFF_MAKE = 74;
    private static final int EXIF_OFF_MODEL = 84;
    private static final int EXIF_OFF_BODY_SERIAL = 96;
    private static final int EXIF_OFF_EXIF_IFD = 110;
    private static final int EXIF_OFF_RAT_EXPOSURE = 188;
    private static final int EXIF_OFF_RAT_FNUMBER = 196;
    private static final int EXIF_OFF_RAT_FOCAL = 204;
    private static final int EXIF_OFF_LENS_MODEL = 212;
    private static final int EXIF_OFF_LENS_SERIAL = 222;
    private static final int EXIF_OFF_GPS_IFD = 236;
    private static final int EXIF_OFF_GPS_LAT = 278;
    private static final int EXIF_OFF_GPS_LON = 302;

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    /** 每个成功上传落盘的安全图片，用例结束逐一删除，不覆盖历史图片。 */
    private final List<File> producedFiles = new ArrayList<>();

    private Object msgSecCheckStub;

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 请求构造与响应解析
     * @param applicationContext 用于反射定位上传控制器与发布服务
     */
    ExifStripTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper,
                  ApplicationContext applicationContext) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入上传用户与发布所需分类。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_03");
        cleanupFixtures();
        producedFiles.clear();
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                U, "es03上传用户", "19900000311", "普通用户", "test-password", "/avatar/" + U + ".png", "0", "正常");
        jdbc.update("INSERT INTO pclist (pcid, pcname, sort) VALUES (?,?,?)", C1, "风光", 1);
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具与落盘文件，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM work_embedding WHERE pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'es03-%' OR pid LIKE 'es03-%')");
        jdbc.update("DELETE FROM work_exif WHERE pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'es03-%' OR pid LIKE 'es03-%')");
        jdbc.update("DELETE FROM pwork WHERE uid LIKE 'es03-%' OR pid LIKE 'es03-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'es03-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'es03-%'");
        for (File file : producedFiles) {
            if (file.exists()) {
                file.delete();
            }
        }
        producedFiles.clear();
    }

    // ------------------------------------------------------------------
    // 冻结断言用例
    // ------------------------------------------------------------------

    /** T03-38 上传含六项EXIF/GPS/机身和镜头序列号的真实JPEG：200，六项与图内值一致且仅六键，落盘文件无EXIF段、无序列号且可解码。 */
    @Test
    void es_01_exifJpegReturnsSixValuesAndStripsFile() throws Exception {
        JsonNode data = assertUploadOk(upload("exif.jpg", "image/jpeg", exifJpegBytes()));
        assertExifValues(data.get("exif"));
        assertStoredSafeImage(data.get("url").asText());
    }

    /** T03-39 上传无EXIF的真实PNG：200，六个键不消失且全为null，落盘文件可解码且无EXIF痕迹。 */
    @Test
    void es_02_pngWithoutExifSucceedsWithNulls() throws Exception {
        JsonNode data = assertUploadOk(upload("plain.png", "image/png", pngBytes()));
        assertExifAllNull(data.get("exif"));
        assertStoredSafeImage(data.get("url").asText());
    }

    /** T03-40 恰好10MB（10485760字节）且可解码JPEG走真实嵌入式HTTP：200，Spring 10MB配置边界语义，六键齐全。 */
    @Test
    void es_03_exactly10MbJpegAcceptedOverRealHttp() throws Exception {
        RawHttpResult result = uploadOverRealHttp("ten-mb.jpg", "image/jpeg", paddedJpeg(LIMIT_10MB),
                token(U, "普通用户"));
        JsonNode json = assertRealEnvelope(result, 200, "ok");
        JsonNode data = json.get("data");
        assertThat(data.isNull()).isFalse();
        assertKeys(data, "url", "exif", "exifStripped");
        assertThat(data.get("exifStripped").isBoolean()).isTrue();
        assertThat(data.get("exifStripped").asBoolean()).isTrue();
        assertExifAllNull(data.get("exif"));
        assertStoredSafeImage(data.get("url").asText());
    }

    /** T03-41 10MB+1字节（10485761）JPEG走真实嵌入式HTTP：框架413 payload_too_large，MockMvc 不足以证明。 */
    @Test
    void es_04_over10MbJpegIs413OverRealHttp() throws Exception {
        RawHttpResult result = uploadOverRealHttp("eleven-mb.jpg", "image/jpeg", paddedJpeg(LIMIT_10MB + 1L),
                token(U, "普通用户"));
        JsonNode json = assertRealEnvelope(result, 413, "payload_too_large");
        assertThat(json.get("data").isNull()).isTrue();
    }

    /** T03-42 缺文件与零字节文件逐例 400 invalid_request。 */
    @Test
    void es_05_missingOrEmptyFileIs400() throws Exception {
        MvcResult noPart = mvc.perform(multipart(UPLOAD_PATH)
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertError(noPart, 400, "invalid_request");
        MvcResult emptyFile = upload("empty.jpg", "image/jpeg", new byte[0]);
        assertError(emptyFile, 400, "invalid_request");
    }

    /** T03-43 文本改jpg后缀、GIF改png后缀、损坏JPEG、元数据解析失败图逐例 422 invalid_image，不按扩展名信任。 */
    @Test
    void es_06_fakeOrCorruptImagesAre422() throws Exception {
        MvcResult[] cases = new MvcResult[]{
                upload("fake.jpg", "image/jpeg", "这不是图片，只是改名为jpg的文本。".getBytes(StandardCharsets.UTF_8)),
                upload("fake.png", "image/png", gifBytes()),
                upload("broken.jpg", "image/jpeg", truncatedJpeg()),
                upload("badmeta.jpg", "image/jpeg", badExifJpegBytes())
        };
        for (MvcResult result : cases) {
            assertError(result, 422, "invalid_image");
        }
    }

    /** T03-44 注入ExifStripper剥离失败：422 invalid_image，不能发布未剥离原图。 */
    @Test
    void es_07_stripFailureIs422() throws Exception {
        Object controller = findControllerBean();
        Field field = controller == null ? null : findCollaboratorField(controller, "ExifStripper");
        if (controller != null) {
            assertThat(field).as("WorkUploadController 未注入 ExifStripper，上传未按契约剥离").isNotNull();
        }
        Object original = null;
        if (field != null) {
            original = installThrowingProxy(controller, field, new RuntimeException("模拟剥离失败"));
        }
        try {
            assertError(upload("exif.jpg", "image/jpeg", exifJpegBytes()), 422, "invalid_image");
        } finally {
            if (field != null) {
                restoreField(controller, field, original);
            }
        }
    }

    /** T03-45 无凭证上传 401 unauthorized。 */
    @Test
    void es_08_uploadWithoutTokenIs401() throws Exception {
        MvcResult result = mvc.perform(multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "exif.jpg", "image/jpeg", exifJpegBytes()))).andReturn();
        assertError(result, 401, "unauthorized");
    }

    /** T03-46 注入WorkStorage未知文件IO失败：500 internal_error，未知IO不能伪装422或成功。 */
    @Test
    void es_09_storageIoFailureIs500() throws Exception {
        Object controller = findControllerBean();
        Field field = controller == null ? null : findCollaboratorField(controller, "WorkStorage");
        if (controller != null) {
            assertThat(field).as("WorkUploadController 未注入 WorkStorage，落盘未按契约适配").isNotNull();
        }
        Object original = null;
        if (field != null) {
            original = installThrowingProxy(controller, field, new IOException("模拟存储IO故障"));
        }
        try {
            assertError(upload("exif.jpg", "image/jpeg", exifJpegBytes()), 500, "internal_error");
        } finally {
            if (field != null) {
                restoreField(controller, field, original);
            }
        }
    }

    /** T03-47 上传→发布→库六项关联：imageUrl与exif取T03-38真实上传响应，201待审核，详情404、mine能读，不新增uploadId。 */
    @Test
    void es_10_uploadThenPublishLinksExif() throws Exception {
        installMsgSecCheckNoop();
        JsonNode uploadData = assertUploadOk(upload("exif.jpg", "image/jpeg", exifJpegBytes()));
        String url = uploadData.get("url").asText();
        JsonNode exif = uploadData.get("exif");
        assertExifValues(exif);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "山海");
        body.put("desc", "晨光");
        body.put("imageUrl", url);
        body.put("categoryId", C1);
        body.put("exif", objectMapper.convertValue(exif, Map.class));
        MvcResult publish = mvc.perform(post("/pwork")
                .header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        JsonNode json = assertEnvelope(publish, 201, "ok");
        JsonNode data = json.get("data");
        assertThat(data.isNull()).isFalse();
        assertKeys(data, "pid", "status");
        String pid = data.get("pid").asText();
        assertThat(pid).matches(ID_REGEX);
        assertThat(data.get("status").asText()).isEqualTo("待审核");
        Map<String, Object> row = jdbc.queryForMap("SELECT uid, wstatus FROM pwork WHERE pid=?", pid);
        assertThat(row.get("uid")).isEqualTo(U);
        assertThat(row.get("wstatus")).isEqualTo("待审核");
        Map<String, Object> exifRow = jdbc.queryForMap(
                "SELECT focal, aperture, shutter, iso, lens, camera FROM work_exif WHERE pid=?", pid);
        assertThat(exifRow.get("focal")).isEqualTo(35);
        assertThat(new BigDecimal(String.valueOf(exifRow.get("aperture"))).compareTo(new BigDecimal("2.8")))
                .isEqualTo(0);
        assertThat(exifRow.get("shutter")).isEqualTo("1/250");
        assertThat(exifRow.get("iso")).isEqualTo(100);
        assertThat(exifRow.get("lens")).isEqualTo("TestLens");
        assertThat(exifRow.get("camera")).isEqualTo("TestCamera");
        assertError(mvc.perform(get("/pwork/" + pid)).andReturn(), 404, "not_found");
        MvcResult mine = mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户"))).andReturn();
        JsonNode mineData = assertEnvelope(mine, 200, "ok").get("data");
        assertThat(mineData.get("list").isArray()).isTrue();
        boolean found = false;
        for (JsonNode item : mineData.get("list")) {
            if (pid.equals(item.get("pid").asText())) {
                found = true;
            }
        }
        assertThat(found).as("mine 应包含新发布作品").isTrue();
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private MvcResult upload(String filename, String contentType, byte[] bytes) throws Exception {
        return mvc.perform(multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", filename, contentType, bytes))
                .header("Authorization", token(U, "普通用户"))).andReturn();
    }

    private JsonNode assertUploadOk(MvcResult result) throws Exception {
        JsonNode json = assertEnvelope(result, 200, "ok");
        assertThat(json.get("data").isNull()).isFalse();
        JsonNode data = json.get("data");
        assertKeys(data, "url", "exif", "exifStripped");
        JsonNode url = data.get("url");
        assertThat(url.isTextual()).isTrue();
        assertThat(url.asText()).isNotEmpty();
        assertThat(data.get("exifStripped").isBoolean()).isTrue();
        assertThat(data.get("exifStripped").asBoolean()).isTrue();
        assertExifShape(data.get("exif"));
        return data;
    }

    private void assertExifShape(JsonNode exif) {
        assertThat(exif.isObject()).isTrue();
        assertKeys(exif, EXIF_KEYS);
        assertNullableNumber(exif.get("focal"));
        assertNullableNumber(exif.get("aperture"));
        assertNullableText(exif.get("shutter"));
        assertNullableInteger(exif.get("iso"));
        assertNullableText(exif.get("lens"));
        assertNullableText(exif.get("camera"));
    }

    private void assertExifValues(JsonNode exif) {
        assertExifShape(exif);
        assertThat(exif.get("focal").isIntegralNumber()).isTrue();
        assertThat(exif.get("focal").asInt()).isEqualTo(35);
        assertThat(exif.get("aperture").isNumber()).isTrue();
        assertThat(exif.get("aperture").asDouble()).isEqualTo(2.8);
        assertThat(exif.get("shutter").asText()).isEqualTo("1/250");
        assertThat(exif.get("iso").asInt()).isEqualTo(100);
        assertThat(exif.get("lens").asText()).isEqualTo("TestLens");
        assertThat(exif.get("camera").asText()).isEqualTo("TestCamera");
    }

    private void assertExifAllNull(JsonNode exif) {
        assertExifShape(exif);
        for (String key : EXIF_KEYS) {
            assertThat(exif.get(key).isNull()).as("无EXIF图片的 %s 应为null", key).isTrue();
        }
    }

    private void assertNullableNumber(JsonNode node) {
        assertThat(node.isNull() || node.isNumber()).isTrue();
    }

    private void assertNullableInteger(JsonNode node) {
        assertThat(node.isNull() || node.isIntegralNumber()).isTrue();
    }

    private void assertNullableText(JsonNode node) {
        assertThat(node.isNull() || node.isTextual()).isTrue();
    }

    /**
     * 校验安全URL格式并按固定存储目录解析实际落盘文件：
     * 文件必须存在、可解码为32x32图片、不含Exif段/序列号，并登记到清理清单。
     */
    private File assertStoredSafeImage(String url) throws Exception {
        assertThat(url).isNotBlank();
        assertThat(url).startsWith(URL_PREFIX);
        String name = url.substring(URL_PREFIX.length());
        assertThat(name).matches("[A-Za-z0-9._-]{1,128}");
        File file = new File(System.getProperty("user.dir"), UPLOAD_DIR + File.separator + name);
        assertThat(file.exists()).as("上传后应落盘安全图片: %s", file.getAbsolutePath()).isTrue();
        producedFiles.add(file);
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertThat(countOf(bytes, "Exif")).as("落盘文件不得含EXIF段").isZero();
        assertThat(countOf(bytes, "eXIf")).as("落盘PNG不得含eXIf块").isZero();
        assertThat(countOf(bytes, BODY_SERIAL)).as("落盘文件不得含机身序列号").isZero();
        assertThat(countOf(bytes, LENS_SERIAL)).as("落盘文件不得含镜头序列号").isZero();
        BufferedImage decoded = ImageIO.read(file);
        assertThat(decoded).as("落盘文件必须可解码为图片").isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(IMAGE_SIZE);
        assertThat(decoded.getHeight()).isEqualTo(IMAGE_SIZE);
        return file;
    }

    private JsonNode assertEnvelope(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode json = parseJson(result.getResponse().getContentAsString());
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).isNotEmpty();
        return json;
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        JsonNode json = assertEnvelope(result, status, code);
        assertThat(json.get("data").isNull()).isTrue();
    }

    private JsonNode assertRealEnvelope(RawHttpResult result, int status, String code) throws Exception {
        assertThat(result.status).isEqualTo(status);
        JsonNode json = parseJson(result.body);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).isNotEmpty();
        return json;
    }

    private JsonNode parseJson(String body) throws Exception {
        assertThat(body).doesNotContain("openid", "session_key", "AppSecret", "appsecret",
                "pwd", "password", "Bearer ", "Exception", "\tat ", BODY_SERIAL, LENS_SERIAL);
        return objectMapper.readTree(body);
    }

    private void assertTopLevelKeys(JsonNode json) {
        Set<String> keys = new HashSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("code", "msg", "data");
    }

    private void assertKeys(JsonNode node, String... expected) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(expected);
    }

    /**
     * 通过真实嵌入式HTTP随机端口发送手写multipart请求：
     * 只有真实Tomcat才会执行Spring的10MB限制，MockMvc构造不出框架413。
     */
    private RawHttpResult uploadOverRealHttp(String filename, String contentType, byte[] bytes,
                                             String authorization) throws Exception {
        assertThat(applicationContext).isInstanceOf(ServletWebServerApplicationContext.class);
        ServletWebServerApplicationContext web = (ServletWebServerApplicationContext) applicationContext;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] multipartBody = body.toByteArray();
        URL url = new URL("http://127.0.0.1:" + web.getWebServer().getPort() + UPLOAD_PATH);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        connection.setRequestProperty("Authorization", authorization);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setFixedLengthStreamingMode(multipartBody.length);
        connection.getOutputStream().write(multipartBody);
        connection.getOutputStream().close();
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        connection.disconnect();
        return new RawHttpResult(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static class RawHttpResult {
        final int status;
        final String body;

        RawHttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // ------------------------------------------------------------------
    // 图像与EXIF夹具（类内生成，不落共享资源）
    // ------------------------------------------------------------------

    private static BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                image.setRGB(x, y, (x * 7 << 16) | (y * 7 << 8) | 0x40);
            }
        }
        return image;
    }

    private static byte[] plainJpegBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(sampleImage(), "jpg", out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] pngBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(sampleImage(), "png", out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] gifBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(sampleImage(), "gif", out)).isTrue();
        return out.toByteArray();
    }

    /** 合法JPEG+含六项EXIF/GPS/双序列号的APP1段，插入SOI之后；自检含Exif且可解码。 */
    private static byte[] exifJpegBytes() throws Exception {
        byte[] jpeg = spliceApp1(plainJpegBytes(), exifApp1Payload());
        assertThat(countOf(jpeg, "Exif")).isEqualTo(1);
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
        return jpeg;
    }

    /** 手工拼的损坏JPEG：截断的合法头后接垃圾数据，解码必失败，不依赖ImageIO构造异常。 */
    private static byte[] badExifJpegBytes() throws Exception {
        byte[] full = plainJpegBytes();
        byte[] bad = new byte[64];
        int keep = Math.min(full.length, 16);
        System.arraycopy(full, 0, bad, 0, keep);
        for (int i = keep; i < bad.length; i++) {
            bad[i] = (byte) (0x60 + i);
        }
        return bad;
    }

    /** 截断的合法JPEG：切掉EOI前的图像数据，解码必失败。 */
    private static byte[] truncatedJpeg() throws Exception {
        byte[] full = plainJpegBytes();
        int keep = full.length * 2 / 3;
        byte[] cut = new byte[keep];
        System.arraycopy(full, 0, cut, 0, keep);
        return cut;
    }

    /** 合法JPEG后补零至指定总长：JPEG解码器在EOI后忽略尾随字节，仍可解码。 */
    private static byte[] paddedJpeg(long totalSize) throws Exception {
        byte[] base = plainJpegBytes();
        assertThat((long) base.length).isLessThan(totalSize);
        byte[] padded = new byte[(int) totalSize];
        System.arraycopy(base, 0, padded, 0, base.length);
        return padded;
    }

    /** 把APP1段拼接到JPEG的SOI之后，段长含两个长度字节。 */
    private static byte[] spliceApp1(byte[] jpeg, byte[] payload) {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE1);
        // JPEG 段长度是大端（big-endian），而 TIFF 内部是小端；这里必须用大端，
        // 否则 APP1 长度错位，ImageIO 会抛「No image data」。
        writeShortBe(segment, payload.length + 2);
        segment.write(payload, 0, payload.length);
        byte[] app1 = segment.toByteArray();
        byte[] out = new byte[2 + app1.length + jpeg.length - 2];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    /**
     * 手工构造Exif APP1载荷（小端TIFF）：
     * IFD0=Make/Model/Exif指针/GPS指针/机身序列号；Exif IFD=曝光1/250、光圈2.8、ISO100、焦距35、
     * 镜头型号TestLens、镜头序列号；GPS IFD=版本/纬度39度54分/经度116度24分。
     * 各数据区写完后按EXIF_OFF_*常量零填充对齐到声明偏移，总长326字节自检。
     */
    private static byte[] exifApp1Payload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShortLe(out, 0x4949);
        writeShortLe(out, 42);
        writeIntLe(out, 8);
        writeShortLe(out, 5);
        writeEntry(out, 0x010F, 2, 9, EXIF_OFF_MAKE);
        writeEntry(out, 0x0110, 2, 11, EXIF_OFF_MODEL);
        writeEntry(out, 0x8769, 4, 1, EXIF_OFF_EXIF_IFD);
        writeEntry(out, 0x8825, 4, 1, EXIF_OFF_GPS_IFD);
        writeEntry(out, 0xA431, 2, 13, EXIF_OFF_BODY_SERIAL);
        writeIntLe(out, 0);
        writeAscii(out, "TestMake");
        padTo(out, EXIF_OFF_MODEL);
        writeAscii(out, "TestCamera");
        padTo(out, EXIF_OFF_BODY_SERIAL);
        writeAscii(out, BODY_SERIAL);
        padTo(out, EXIF_OFF_EXIF_IFD);
        writeShortLe(out, 6);
        writeEntry(out, 0x829A, 5, 1, EXIF_OFF_RAT_EXPOSURE);
        writeEntry(out, 0x829D, 5, 1, EXIF_OFF_RAT_FNUMBER);
        writeEntry(out, 0x8827, 3, 1, 100);
        writeEntry(out, 0x920A, 5, 1, EXIF_OFF_RAT_FOCAL);
        writeEntry(out, 0xA434, 2, 9, EXIF_OFF_LENS_MODEL);
        writeEntry(out, 0xA435, 2, 14, EXIF_OFF_LENS_SERIAL);
        writeIntLe(out, 0);
        writeRational(out, 1, 250);
        writeRational(out, 28, 10);
        writeRational(out, 35, 1);
        writeAscii(out, "TestLens");
        padTo(out, EXIF_OFF_LENS_SERIAL);
        writeAscii(out, LENS_SERIAL);
        padTo(out, EXIF_OFF_GPS_IFD);
        writeShortLe(out, 3);
        writeEntry(out, 0x0000, 1, 4, 0x00030002);
        writeEntry(out, 0x0002, 5, 3, EXIF_OFF_GPS_LAT);
        writeEntry(out, 0x0004, 5, 3, EXIF_OFF_GPS_LON);
        writeIntLe(out, 0);
        writeRational(out, 39, 1);
        writeRational(out, 54, 1);
        writeRational(out, 0, 1);
        writeRational(out, 116, 1);
        writeRational(out, 24, 1);
        writeRational(out, 0, 1);
        byte[] tiff = out.toByteArray();
        assertThat(tiff.length).as("EXIF TIFF 布局自检").isEqualTo(326);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write('E');
        payload.write('x');
        payload.write('i');
        payload.write('f');
        payload.write(0);
        payload.write(0);
        payload.write(tiff, 0, tiff.length);
        return payload.toByteArray();
    }

    private static void writeShortBe(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShortLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeEntry(ByteArrayOutputStream out, int tag, int type, int count, int value) {
        writeShortLe(out, tag);
        writeShortLe(out, type);
        writeIntLe(out, count);
        writeIntLe(out, value);
    }

    private static void writeRational(ByteArrayOutputStream out, long numerator, long denominator) {
        writeIntLe(out, (int) numerator);
        writeIntLe(out, (int) denominator);
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
        out.write(0);
    }

    /** 零填充对齐到声明偏移；布局常量若落在已写区域之前，说明常量与写入顺序脱节，立即失败。 */
    private static void padTo(ByteArrayOutputStream out, int target) {
        assertThat(out.size()).as("EXIF 布局常量不得落在已写区域").isLessThanOrEqualTo(target);
        while (out.size() < target) {
            out.write(0);
        }
    }

    private static int countOf(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.US_ASCII);
        int count = 0;
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 反射替身（本卡接口未合入时跳过安装，由路由缺失自然红）
    // ------------------------------------------------------------------

    private Object findControllerBean() {
        for (String name : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(name);
                if (bean != null && bean.getClass().getSimpleName().startsWith("WorkUploadController")) {
                    return bean;
                }
            } catch (Exception ignored) {
                // 惰性Bean或FactoryBean解析失败不影响定位其它Bean
            }
        }
        log.info("WorkUploadController 尚未合入，跳过故障注入，路由缺失会自然红");
        return null;
    }

    private Field findCollaboratorField(Object bean, String typeFragment) {
        for (Class<?> current = bean.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getSimpleName().contains(typeFragment)) {
                    return field;
                }
            }
        }
        return null;
    }

    private Object installThrowingProxy(Object bean, Field field, final Throwable failure) throws Exception {
        Class<?> type = field.getType();
        field.setAccessible(true);
        Object original = field.get(bean);
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        throw failure;
                    }
                });
        field.set(bean, proxy);
        return original;
    }

    private void restoreField(Object bean, Field field, Object original) throws Exception {
        field.setAccessible(true);
        field.set(bean, original);
    }

    /**
     * 把 PworkServiceImpl 的 MsgSecCheck 替换为无操作替身，发布不依赖真实微信文本安全服务。
     * 事务代理下必须写入 AopTestUtils 解出的真实 target，否则真实实现仍会调用无凭据的微信服务。
     * 实现尚未注入该字段时只记日志并返回false，用例继续走到HTTP断言自然红，夹具不抛异常。
     */
    private boolean installMsgSecCheckNoop() {
        for (String name : applicationContext.getBeanDefinitionNames()) {
            if (!name.toLowerCase(Locale.ROOT).contains("pwork")) {
                continue;
            }
            Object bean;
            try {
                bean = applicationContext.getBean(name);
            } catch (Exception missing) {
                continue;
            }
            Object target = AopTestUtils.getUltimateTargetObject(bean);
            if (!target.getClass().getSimpleName().startsWith("PworkServiceImpl")) {
                continue;
            }
            Field field = findCollaboratorField(target.getClass(), "MsgSecCheck");
            if (field == null) {
                log.warn("PworkServiceImpl 未注入 MsgSecCheck，发布未按契约接入文本安全检查，用例以HTTP断言形式红");
                return false;
            }
            try {
                field.setAccessible(true);
                msgSecCheckStub = mock(field.getType(), invocation -> null);
                field.set(target, msgSecCheckStub);
                log.info("已把 PworkServiceImpl 的 MsgSecCheck 替换为无操作替身 bean={} target={}",
                        name, target.getClass().getSimpleName());
                return true;
            } catch (Exception failed) {
                throw new AssertionError("安装 MsgSecCheck 替身失败", failed);
            }
        }
        log.info("PworkServiceImpl 尚未合入，跳过 MsgSecCheck 替身安装，发布路由缺失会自然红");
        return false;
    }
}
