# -*- coding: utf-8 -*-
"""
生成《技术清单.docx》（给指导教师的版本，含中文宋体/黑体与 1.5 倍行距设置）

设计说明：
1. 中文字体必须同时设置 w:eastAsia，否则 Word 会按默认西文字体渲染，宋体不生效。
2. 正文小四（12pt）宋体、1.5 倍行距；标题黑体加粗，逐级递减字号。
3. 表格选用 Table Grid 内置样式，表头行加粗。
"""
import sys

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Pt

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

OUT = "技术清单_20235080902011_朱柏缘.docx"

SONG = "宋体"
HEI = "黑体"
LATIN = "Times New Roman"


def style_run(run, cn=SONG, latin=LATIN, size=12, bold=False):
    """统一设置中英文字体、字号与加粗。"""
    run.font.name = latin
    run.font.size = Pt(size)
    run.bold = bold
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.get_or_add_rFonts()
    rfonts.set(qn("w:eastAsia"), cn)
    rfonts.set(qn("w:ascii"), latin)
    rfonts.set(qn("w:hAnsi"), latin)


def para(doc, text, cn=SONG, size=12, bold=False, align=None,
         indent_chars=0, space_before=0, space_after=6, line=1.5):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.line_spacing = line
    pf.space_before = Pt(space_before)
    pf.space_after = Pt(space_after)
    if indent_chars:
        pf.left_indent = Pt(size * indent_chars)
    if align is not None:
        p.alignment = align
    style_run(p.add_run(text), cn=cn, size=size, bold=bold)
    return p


def main():
    doc = Document()

    # 全局默认字体，避免新增段落回落到 Calibri
    normal = doc.styles["Normal"]
    normal.font.name = LATIN
    normal.font.size = Pt(12)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), SONG)

    # ---------- 标题与抬头 ----------
    para(doc, "技　术　清　单", cn=HEI, size=18, bold=True,
         align=WD_ALIGN_PARAGRAPH.CENTER, space_after=12)
    para(doc, "题目：基于微信小程序与 Spring Boot 的智能摄影作品社区系统设计与实现",
         size=11, indent_chars=0, space_after=2)
    para(doc, "姓名：朱柏缘　　学号：20235080902011　　指导教师：徐进", size=11, space_after=2)
    para(doc, "二级学院：数据科学与计算机学院　　专业：软件工程", size=11, space_after=14)

    # ---------- 一、总体结构 ----------
    para(doc, "一、系统总体结构", cn=HEI, size=14, bold=True, space_before=6, space_after=6)
    para(doc, "系统分为三部分：微信小程序（用户端）、Spring Boot 后端服务（业务主体）、"
              "AI 增强模块。AI 仅作为增强模块挂接在“作品搜索”与“作品详情”两个既有功能上，"
              "不参与系统主体业务。")

    # ---------- 二、技术栈总览 ----------
    para(doc, "二、技术栈总览", cn=HEI, size=14, bold=True, space_before=10, space_after=6)
    rows = [
        ("层次", "主要技术", "用途"),
        ("用户端", "原生微信小程序（WXML / WXSS / JavaScript）", "摄影社区与画廊的移动端界面"),
        ("后端服务（主体）", "Java 8、Spring Boot 2.4.5、MyBatis-Plus 3.4.2、PageHelper 1.4.2",
         "用户、作品、评论、点赞、收藏、约拍等业务逻辑"),
        ("数据存储", "MySQL 8、Druid 连接池", "业务数据持久化"),
        ("缓存与限流", "Redis", "热点数据缓存、热门作品榜、接口限流"),
        ("安全", "JWT（jjwt 0.11.5）、BCrypt", "登录鉴权与密码加密"),
        ("后台管理端", "Thymeleaf、Layui（layuimini）、ECharts", "后台管理与数据统计"),
        ("AI 增强层", "Chinese-CLIP、metadata-extractor、NIMA、多模态视觉大模型",
         "语义检索、拍摄方案复刻、美学评估"),
        ("部署", "Docker、Docker Compose", "一键启动数据库、缓存、后端与推理服务"),
    ]
    table = doc.add_table(rows=0, cols=3)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for ri, row_data in enumerate(rows):
        cells = table.add_row().cells
        for ci, text in enumerate(row_data):
            p = cells[ci].paragraphs[0]
            p.paragraph_format.line_spacing = 1.15
            p.paragraph_format.space_after = Pt(2)
            style_run(p.add_run(text), cn=(HEI if ri == 0 else SONG),
                      size=10.5, bold=(ri == 0))
    para(doc, "", size=6, space_after=2)

    # ---------- 三、小程序端 ----------
    para(doc, "三、微信小程序端（用户端）", cn=HEI, size=14, bold=True,
         space_before=10, space_after=6)
    for t in [
        "· 原生微信小程序开发：WXML / WXSS / JavaScript",
        "· 自定义组件、自定义 tabBar、页面分包",
        "· 小程序原生 API：网络请求封装、图片上传与预览、本地存储",
        "· 微信内容安全接口（security.imgSecCheck / msgSecCheck）：上传图片与文本审核",
        "· 计划引入：Vant Weapp 或 WeUI 组件库，统一 UI 风格、提升开发效率",
    ]:
        para(doc, t, indent_chars=0.5, space_after=3)

    # ---------- 四、后端服务 ----------
    para(doc, "四、后端服务（系统主体）", cn=HEI, size=14, bold=True,
         space_before=10, space_after=6)
    for t in [
        "· Java 8 + Spring Boot 2.4.5（Spring MVC）：承载全部业务逻辑",
        "· MyBatis-Plus 3.4.2 + Maven：持久层与项目管理",
        "· PageHelper 1.4.2：分页查询",
        "· MySQL 8 + Druid 连接池：数据存储",
        "· JWT（jjwt 0.11.5）+ BCrypt 加密：登录鉴权与密码安全",
        "· Thymeleaf + Layui（layuimini）+ ECharts：后台管理端与数据统计",
        "· Multipart 文件上传 + 本地文件存储（可扩展至对象存储）",
        "· 业务模块：用户、作品、分类、评论、点赞、收藏、约拍/订单、系统日志",
    ]:
        para(doc, t, indent_chars=0.5, space_after=3)

    # ---------- 五、缓存与中间件 ----------
    para(doc, "五、缓存与中间件（Redis）", cn=HEI, size=14, bold=True,
         space_before=10, space_after=6)
    for t in [
        "· 定位：只读缓存与派生数据的加速层，不作为业务数据的唯一来源",
        "· 热门作品排行榜：有序集合按点赞与浏览量排序，可随时由 MySQL 重建",
        "· 作品列表与详情读缓存：采用旁路缓存策略，写操作后删除缓存",
        "· 接口限流与登录失败计数：配合图形验证码，防止恶意请求",
        "· 部署：与 MySQL、后端服务一并由 Docker Compose 启动",
    ]:
        para(doc, t, indent_chars=0.5, space_after=3)

    # ---------- 六、AI 增强模块 ----------
    para(doc, "六、AI 增强模块（创新点，独立于主体业务）", cn=HEI, size=14, bold=True,
         space_before=10, space_after=6)
    para(doc, "1. 跨模态语义检索（以文搜图 / 以图搜图）", bold=True, space_before=4, space_after=3)
    for t in [
        "· Chinese-CLIP（ViT-B/16，约 1.88 亿参数）提取图像与中文文本特征向量，官方支持 ONNX 导出",
        "· 备选方案：云端多模态向量接口（通义 embedding-vision 系列，输出 768 / 1152 维向量）",
        "· 向量存储与检索：作品向量表 + 余弦相似度计算；数据规模增长后可引入专用向量索引",
    ]:
        para(doc, t, indent_chars=1.0, space_after=3)

    para(doc, "2. 拍摄方案复刻", bold=True, space_before=4, space_after=3)
    for t in [
        "· metadata-extractor（Java 库）：解析 EXIF 拍摄参数（光圈、快门、ISO、焦距、镜头型号）",
        "· 多模态视觉理解大模型：对 EXIF 被剥离的图片推测拍摄条件与场景，以结构化 JSON 输出",
        "· 拍摄参数脱敏：上传时剥离 GPS 与设备序列号，仅保留摄影参数",
    ]:
        para(doc, t, indent_chars=1.0, space_after=3)

    para(doc, "3. 图像美学评估（用于“精选作品”排序参考）", bold=True, space_before=4, space_after=3)
    for t in [
        "· NIMA 美学评估模型：MobileNet 骨干、AVA 数据集预训练权重（开源）",
    ]:
        para(doc, t, indent_chars=1.0, space_after=3)

    para(doc, "4. 增强模块的工程实现", bold=True, space_before=4, space_after=3)
    for t in [
        "· 推理服务：Python FastAPI + PyTorch / ONNX Runtime（独立进程），或 Java 侧 ONNX Runtime 内嵌推理",
        "· 异步任务与状态管理：作品上传后异步建索引，状态分「待处理 / 已完成 / 失败可重试」",
        "· 服务编排与降级：超时、熔断、结果落库缓存；模型不可用时自动退回关键词检索等传统实现",
    ]:
        para(doc, t, indent_chars=1.0, space_after=3)

    # ---------- 七、部署与工具 ----------
    para(doc, "七、部署与开发工具", cn=HEI, size=14, bold=True, space_before=10, space_after=6)
    for t in [
        "· Docker + Docker Compose：一键启动数据库、缓存、后端服务与推理服务",
        "· Maven 构建，Git 版本管理",
        "· 开发工具：IntelliJ IDEA、微信开发者工具",
        "· 接口调试：Apifox / Postman",
    ]:
        para(doc, t, indent_chars=0.5, space_after=3)

    # ---------- 八、补充说明 ----------
    para(doc, "八、补充说明", cn=HEI, size=14, bold=True, space_before=10, space_after=6)
    for t in [
        "1. AI 相关模块均为增强性质。关闭增强模块后，系统主体（用户、作品、评论、点赞、"
        "约拍/订单、后台管理）仍可完整运行，业务闭环不受影响。",
        "2. 模型中除多模态大模型需调用云端接口外，其余（图像特征提取、EXIF 解析、美学评估）"
        "均可在本地设备运行，不依赖外部服务。",
        "3. Redis 仅作为缓存与限流使用，业务数据以 MySQL 为准，缓存可在任意时刻重建。",
        "4. 系统主体为摄影作品社区业务，AI 用于提升检索与摄影学习体验，不作为系统主体。",
    ]:
        para(doc, t, space_after=4)

    doc.save(OUT)
    print("已生成:", OUT)


if __name__ == "__main__":
    main()
