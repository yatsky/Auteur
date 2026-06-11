#!/usr/bin/env python3
"""
export-demo-fixtures.py — 把一条已跑通的真实流水线 dump 成 demo fixtures。

用法
====
    docker compose up -d                       # 起服务,跑完一条完整流水线后
    python3 backend/scripts/export-demo-fixtures.py <topic-id>

效果
====
1. 从 docker MySQL 把这条 topic 链路上的所有数据 dump 成 JSON,落到:
       frontend/src/demo/fixtures/*.json
2. 把 backend/storage/ 下相关的图片 / 音频 / 视频 / 封面拷到:
       frontend/public/demo-assets/
3. 把 image_asset / voice_asset / video_asset / cover_asset 里的 url 字段
   重写成 /demo-assets/... 静态路径,demo 站直接 serve
4. 输出一份 fixtures-summary.json 给 fixtures.ts 引用时参考

前置条件
========
- docker compose 起着(auteur-mysql 容器在跑)
- topic id 对应的脚本已经走完整条流水线(脚本+分镜+图+配音+视频)
- python3 + 标准库即可,不需要装 mysql-connector

为什么不用 mysqldump
====================
mysqldump 输出 SQL,demo 这边要的是 JSON,而且要把 url 字段重写。
直接 SELECT JSON_OBJECT 一步到位,字段重写也方便。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import shutil
import sys
from pathlib import Path
from typing import Any

# ──────────────────────────────────────────────────────────────
# 配置
# ──────────────────────────────────────────────────────────────
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_DIR = REPO_ROOT / "frontend" / "src" / "demo" / "fixtures"
ASSETS_DIR = REPO_ROOT / "frontend" / "public" / "demo-assets"
STORAGE_DIR = REPO_ROOT / "backend" / "storage"

MYSQL_CONTAINER = "auteur-mysql"
MYSQL_USER = "root"
MYSQL_PASSWORD = "auteur"  # docker-compose .env.example 默认值;改过的话用 -p 参数覆盖
MYSQL_DB = "auteur"


# ──────────────────────────────────────────────────────────────
# MySQL helpers
# ──────────────────────────────────────────────────────────────
def mysql_exec(sql: str, password: str) -> str:
    """在 docker exec 里跑 SQL,返回 stdout(str)。"""
    cmd = [
        "docker", "exec", "-i", MYSQL_CONTAINER,
        "mysql", f"-u{MYSQL_USER}", f"-p{password}",
        "-D", MYSQL_DB,
        "-N", "-B", "-e", sql,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout


def get_columns(table: str, password: str) -> list[str]:
    """返回某张表的字段名列表。"""
    out = mysql_exec(f"DESCRIBE `{table}`", password)
    return [line.split("\t")[0] for line in out.strip().splitlines() if line]


def dump_rows(table: str, where: str, password: str) -> list[dict[str, Any]]:
    """把表的 row 按 where 过滤后,以 JSON list 形式返回。"""
    cols = get_columns(table, password)
    if not cols:
        return []
    json_pairs = ", ".join(f"'{c}', `{c}`" for c in cols)
    sql = (
        f"SELECT COALESCE("
        f"  JSON_ARRAYAGG(JSON_OBJECT({json_pairs})),"
        f"  JSON_ARRAY()"
        f") FROM `{table}` WHERE {where}"
    )
    out = mysql_exec(sql, password).strip()
    if not out or out == "NULL":
        return []
    # mysql -B 输出会把 \n 转义成字面 \n,JSON 解析前要还原
    out = out.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        # fallback:逐行读出来再合并(超大 JSON 列时碰到过)
        print(f"  ⚠️ JSON parse error on {table} ({e}),tried fallback", file=sys.stderr)
        return []


def dump_one(table: str, where: str, password: str) -> dict[str, Any] | None:
    rows = dump_rows(table, where, password)
    return rows[0] if rows else None


# ──────────────────────────────────────────────────────────────
# 资源拷贝 + URL 重写
# ──────────────────────────────────────────────────────────────
def copy_storage_subdir(subdir: str, dest_subdir: str) -> int:
    """把 backend/storage/<subdir>/ 全量拷到 frontend/public/demo-assets/<dest_subdir>/。
    返回拷贝的文件数。"""
    src = STORAGE_DIR / subdir
    dst = ASSETS_DIR / dest_subdir
    if not src.exists():
        return 0
    dst.mkdir(parents=True, exist_ok=True)
    count = 0
    for f in src.rglob("*"):
        if f.is_file() and not f.name.startswith("."):
            rel = f.relative_to(src)
            target = dst / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, target)
            count += 1
    return count


def rewrite_url(url: str | None, new_prefix: str) -> str | None:
    """把 TOS / file:// / /api/files/... 类型的 url 重写成 /demo-assets/<prefix>/<basename>。"""
    if not url:
        return url
    # 抓最后一段文件名(URL / 本地路径都吃)
    name = url.rstrip("/").split("/")[-1].split("?")[0]
    if not name:
        return url
    return f"/demo-assets/{new_prefix}/{name}"


# ──────────────────────────────────────────────────────────────
# 主流程
# ──────────────────────────────────────────────────────────────
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("topic_id", type=int, help="要导出的 topic id(已跑完整条流水线的那条)")
    parser.add_argument("-p", "--password", default=MYSQL_PASSWORD,
                        help=f"MySQL root 密码(默认 {MYSQL_PASSWORD};跟 .env 的 MYSQL_PASSWORD 一致)")
    args = parser.parse_args()

    print(f"🎬 导出 topic={args.topic_id} 的完整流水线为 demo fixtures...")
    print()

    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    pwd = args.password

    # ── 1. Topic + 关联 script ─────────────────────────────
    topic = dump_one("topic", f"id={args.topic_id}", pwd)
    if not topic:
        print(f"❌ topic id={args.topic_id} 不存在,退出。先跑一条完整流水线后再来。")
        return 1
    save_json("topic.json", topic)

    scripts = dump_rows("script", f"topic_id={args.topic_id}", pwd)
    if not scripts:
        print(f"❌ topic={args.topic_id} 没有关联 script。先生成脚本再来。")
        return 1
    script = scripts[0]  # 取最新那条
    script_id = script["id"]
    save_json("script.json", script)
    save_json("script-sections.json", dump_rows("script_section", f"script_id={script_id}", pwd))

    print(f"   ✓ topic + script (id={script_id})")

    # ── 2. Brainstorm 候选(同 brainstorm_batch_id 的多条) ─
    if topic.get("brainstorm_batch_id"):
        siblings = dump_rows(
            "topic",
            f"brainstorm_batch_id='{topic['brainstorm_batch_id']}'",
            pwd,
        )
        save_json("brainstorm-result.json", siblings)
        print(f"   ✓ brainstorm 候选 × {len(siblings)}")
    else:
        save_json("brainstorm-result.json", [topic])

    # ── 3. Storyboard ─────────────────────────────────────
    shots = dump_rows("storyboard_shot", f"script_id={script_id}", pwd)
    save_json("storyboard.json", shots)
    print(f"   ✓ storyboard shots × {len(shots)}")

    # ── 4. Image / Voice / Video / Cover —— 拷文件 + 重写 url ─
    images_copied = copy_storage_subdir("image", "images")
    voice_copied = copy_storage_subdir("voice", "voice")
    video_copied = copy_storage_subdir("video", "video")
    cover_copied = copy_storage_subdir("cover", "cover")

    images = dump_rows("image_asset", f"script_id={script_id}", pwd)
    for img in images:
        if "url" in img:
            img["url"] = rewrite_url(img["url"], "images")
        if "thumb_url" in img:
            img["thumb_url"] = rewrite_url(img["thumb_url"], "images")
    save_json("images.json", images)

    voices = dump_rows("voice_asset", f"script_id={script_id}", pwd)
    for v in voices:
        for k in ("url", "audio_url", "srt_url"):
            if k in v:
                v[k] = rewrite_url(v[k], "voice")
    save_json("voice.json", voices)

    videos = dump_rows("video_asset", f"script_id={script_id}", pwd)
    for v in videos:
        for k in ("url", "video_url"):
            if k in v:
                v[k] = rewrite_url(v[k], "video")
    save_json("videos.json", videos)

    covers = dump_rows("cover_asset", f"script_id={script_id}", pwd)
    for c in covers:
        for k in ("url", "image_url", "thumb_url"):
            if k in c:
                c[k] = rewrite_url(c[k], "cover")
    save_json("covers.json", covers)

    print(f"   ✓ images × {len(images)} (拷贝 {images_copied} 个文件)")
    print(f"   ✓ voice × {len(voices)} (拷贝 {voice_copied} 个文件)")
    print(f"   ✓ videos × {len(videos)} (拷贝 {video_copied} 个文件)")
    print(f"   ✓ covers × {len(covers)} (拷贝 {cover_copied} 个文件)")

    # ── 5. Preset / DirectorNote / Critic / FactCheck ─────
    if topic.get("preset_id"):
        preset = dump_one("preset", f"id={topic['preset_id']}", pwd)
        save_json("preset.json", preset)

    save_json("director-note-addendum.json", dump_rows(
        "director_note_addendum", f"topic_id={args.topic_id}", pwd))
    save_json("critic-logs.json", dump_rows(
        "critic_log", f"target_id={script_id}", pwd))
    save_json("factcheck-issues.json", dump_rows(
        "fact_check_issue", f"script_id={script_id}", pwd))

    # ── 6. BGM ────────────────────────────────────────────
    save_json("bgm-choice.json", dump_one("script_bgm_choice", f"script_id={script_id}", pwd))
    bgm_tracks = dump_rows("bgm_track", "1=1 LIMIT 20", pwd)  # 任 20 条做曲库展示
    save_json("bgm-tracks.json", bgm_tracks)

    # ── 7. Pipeline runs ──────────────────────────────────
    save_json("pipeline-runs.json", dump_rows(
        "pipeline_run", f"topic_id={args.topic_id} OR script_id={script_id}", pwd))

    # ── 8. Insights / 数据复盘(全局,不绑 topic) ─────────
    save_json("published-videos.json", dump_rows(
        "published_video", "1=1 ORDER BY published_at DESC LIMIT 50", pwd))
    save_json("genre-stats.json", dump_rows("genre_stat_snapshot", "1=1 LIMIT 100", pwd))
    save_json("weekly-reviews.json", dump_rows(
        "weekly_review", "1=1 ORDER BY week_end_date DESC LIMIT 8", pwd))
    save_json("series.json", dump_rows("series", "1=1", pwd))
    save_json("series-hooks.json", dump_rows("series_hook", "1=1 LIMIT 30", pwd))

    # ── 9. Summary ────────────────────────────────────────
    summary = {
        "exportedAt": __import__("datetime").datetime.now().isoformat(),
        "topicId": args.topic_id,
        "scriptId": script_id,
        "topicTitle": topic.get("title"),
        "counts": {
            "shots": len(shots),
            "images": len(images),
            "voices": len(voices),
            "videos": len(videos),
            "covers": len(covers),
        },
        "filesCopied": {
            "images": images_copied,
            "voice": voice_copied,
            "video": video_copied,
            "cover": cover_copied,
        },
    }
    save_json("_summary.json", summary)

    print()
    print("✅ 导出完成")
    print(f"   fixtures: {FIXTURES_DIR}")
    print(f"   assets:   {ASSETS_DIR}")
    print()
    print("下一步:把 fixtures 接进 frontend/src/demo/fixtures.ts 的 fixturesTable")
    print("       (Claude 来做这一步,你只要 commit 这两个目录就行)")
    return 0


def save_json(name: str, data: Any) -> None:
    path = FIXTURES_DIR / name
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    sys.exit(main())
