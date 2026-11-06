# -*- coding: utf-8 -*-
"""
填写／更新《广东培正学院 毕业论文（设计）选题审批表》

2026-10-14 变更：学院下发的空白模板已不在磁盘上，故改为「就地增量填写」——
直接对已填写稿操作，不依赖空白模板，可反复重跑（幂等）。改完 FILL / CHECK 重跑即可。

设计原则：
1. 优先在已有 run 内做字符串替换，保留原字体/字号/间距；只有跨 run 时才重建首 run。
2. 教师的意见/签名栏一律不碰。
3. 勾选项已勾选时视为成功，重复运行不报错。
"""
import copy
import sys

from docx import Document

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

DOC = "选题审批表_20235080902011_朱柏缘_已填写.docx"

# 待填写内容（集中在此，便于核对与修改）
FILL = {
    "学号": "20235080902011",          # 用户 2026-10-14 确认：14 位
    "姓名": "朱柏缘",
    "指导教师职称": "",                  # 留空 = 不修改该单元格；填写后重跑即写入
    "题目名称": "基于微信小程序与 Spring Boot 的智能摄影作品社区系统设计与实现",
}

# 单元格坐标：(表行, 表列)
POS = {
    "学号": (0, 1),
    "姓名": (0, 3),
    "指导教师职称": (2, 3),
    "题目名称": (3, 1),   # 该行跨列合并，取第一个值格
}

# 勾选：{行号: (原文, 替换文)}
CHECK = {
    4: ("设计□", "设计☑"),                          # 类别 -> 设计
    5: ("A.应用研究型 □", "A.应用研究型 ☑"),          # 选题类型 -> 应用研究型
    6: ("C.其他□", "C.其他 ☑"),                      # 选题来源 -> 其他
    7: ("A.学生自拟 □", "A.学生自拟 ☑"),              # 选题方式 -> 学生自拟
}


def replace_in_paragraph(p, old, new):
    """在段落内替换文本：命中单 run 就地替换；跨 run 则重建首 run 以保留格式。"""
    full = "".join(r.text for r in p.runs)
    if old not in full:
        return False
    for r in p.runs:
        if old in r.text:
            r.text = r.text.replace(old, new)
            return True
    p.runs[0].text = full.replace(old, new)
    for r in p.runs[1:]:
        r.text = ""
    return True


def replace_in_cell(cell, old, new):
    for p in cell.paragraphs:
        if replace_in_paragraph(p, old, new):
            return True
    return False


def write_cell(cell, text, ref_paragraph=None):
    """把文本写入单元格；空单元格则新建 run 并复制参考段落的字体属性。"""
    p = cell.paragraphs[0]
    if p.runs:
        p.runs[0].text = text
        for r in p.runs[1:]:
            r.text = ""
    else:
        run = p.add_run(text)
        if ref_paragraph is not None and ref_paragraph.runs:
            ref_rpr = ref_paragraph.runs[0]._element.find(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}rPr"
            )
            if ref_rpr is not None:
                run._element.insert(0, copy.deepcopy(ref_rpr))
    # 段落属性（如居中）从参考段落借用
    if ref_paragraph is not None:
        ref_ppr = ref_paragraph._p.find(
            "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}pPr"
        )
        if ref_ppr is not None:
            ppr = p._p.find(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}pPr"
            )
            if ppr is not None:
                p._p.remove(ppr)
            p._p.insert(0, copy.deepcopy(ref_ppr))


def main():
    d = Document(DOC)
    t = d.tables[0]

    # 参考段落：用「专业」的值单元格（"软件工程"），字体与居中格式最接近普通值单元格
    ref_p = t.rows[1].cells[1].paragraphs[0]
    log = []

    # --- 文本字段（学号 / 姓名 / 职称 / 题目名称）---
    for key, (ri, ci) in POS.items():
        cell = t.rows[ri].cells[ci]
        value = FILL.get(key, "")
        if not value:
            log.append((key, cell.text, "留空，未修改"))
            continue
        log.append((key, cell.text, value))
        write_cell(cell, value, ref_p)

    # --- 四个勾选项（幂等：已勾选也视为成功）---
    for row_idx, (old, new) in CHECK.items():
        cell = t.rows[row_idx].cells[1]
        changed = replace_in_cell(cell, old, new)
        log.append((f"第{row_idx}行勾选", old, new,
                    "本次修改" if changed else ("已勾选" if new in cell.text else "未匹配")))

    d.save(DOC)

    print("=== 填写结果 ===")
    for item in log:
        print(" | ".join(str(x) for x in item))

    # 复核读取
    t2 = Document(DOC).tables[0]
    print("\n=== 复核 ===")
    for ri in range(7):
        print(f"r{ri} {t2.rows[ri].cells[0].text}: {t2.rows[ri].cells[1].text}")
    print("\n教师栏未被触碰:", t2.rows[8].cells[1].text.split("\n")[0][:24])


if __name__ == "__main__":
    main()
