#!/usr/bin/env python3
"""
export-demo-fixtures.py — 把一条已跑通的真实流水线 dump 成 demo fixtures。

用法
====
    # 默认从 application-local.yml 自动读连接信息(host/port/user/password)
    python3 backend/scripts/export-demo-fixtures.py <topic-id>

    # 显式指定连接(覆盖自动读取)
    python3 backend/scripts/export-demo-fixtures.py <topic-id> \\
        --host 127.0.0.1 --port 3306 --user root --password xxx --db auteur

效果
====
1. 从 MySQL 把这条 topic 链路上的所有数据 dump 成 JSON,落到:
       frontend/src/demo/fixtures/*.json
2. 把 backend/storage/ 下相关的图片 / 音频 / 视频 / 封面拷到:
       frontend/public/demo-assets/
3. 把 image_asset / voice_asset / video_asset / cover_asset 里的 url 字段
   重写成 /demo-assets/... 静态路径,demo 站直接 serve
4. 输出一份 fixtures-summary.json 给 fixtures.ts 引用时参考

前置条件
========
- 宿主机能连到 MySQL(本地 / docker 暴露端口 / natapp 转发都行)
- topic id 对应的脚本已经走完整条流水线(脚本+分镜+图+配音+视频)
- 宿主机装了 `mysql` 客户端(brew install mysql-client / apt install mysql-client)
- python3 标准库即可,不需要装 mysql-connector
"""

from __future__ import annotations

import argparse
import json
import re
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
LOCAL_YML = REPO_ROOT / "backend" / "src" / "main" / "resources" / "application-local.yml"


# ──────────────────────────────────────────────────────────────
# 从 application-local.yml 读 datasource(轻量正则,不依赖 PyYAML)
# ──────────────────────────────────────────────────────────────
def parse_local_yml() -> dict[str, str]:
    """返回 {host, port, db, user, password}。读不到任何字段返回 {}。"""
    if not LOCAL_YML.exists():
        return {}
    text = LOCAL_YML.read_text(encoding="utf-8")
    # url: "jdbc:mysql://HOST:PORT/DB?..."
    m_url = re.search(r'url:\s*["\']?jdbc:mysql://([^:/]+):(\d+)/(\w+)', text)
    m_user = re.search(r'^\s*username:\s*["\']?(\S+?)["\']?\s*(?:#|$)', text, re.MULTILINE)
    m_pass = re.search(r'^\s*password:\s*["\']?(.+?)["\']?\s*(?:#|$)', text, re.MULTILINE)
    cfg: dict[str, str] = {}
    if m_url:
        cfg["host"] = m_url.group(1)
        cfg["port"] = m_url.group(2)
        cfg["db"] = m_url.group(3)
    if m_user:
        cfg["user"] = m_user.group(1)
    if m_pass:
        cfg["password"] = m_pass.group(1)
    return cfg


# ──────────────────────────────────────────────────────────────
# MySQL helpers —— 用宿主机 mysql 客户端,不走 docker exec
# ──────────────────────────────────────────────────────────────
class DB:
    def __init__(self, host: str, port: str, user: str, password: str, db: str):
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.db = db

    def exec(self, sql: str, max_retries: int = 4) -> str:
        """跑 SQL,自动重试网络瞬断(natapp 隧道偶尔丢包)。"""
        cmd = [
            "mysql",
            f"-h{self.host}",
            f"-P{self.port}",
            f"-u{self.user}",
            f"-p{self.password}",
            "-D", self.db,
            # -N(去表头)+ -B(batch tab 分隔)+ --raw(关掉 backslash escape,
            # 否则 SELECT JSON_OBJECT 输出的 \n / \" 会被 mysql 二次转义成 \\n / \\")
            "-N", "-B", "--raw",
            "--default-character-set=utf8mb4",
            "-e", sql,
        ]
        last_err = ""
        for attempt in range(max_retries):
            proc = subprocess.run(cmd, capture_output=True, text=True)
            if proc.returncode == 0:
                return proc.stdout
            last_err = proc.stderr.strip() or proc.stdout.strip()
            # 网络抖动类:重试;其他错(语法/权限)立刻挂
            if "Lost connection" in last_err or "Can't connect" in last_err or "communication packet" in last_err:
                wait = 2 ** attempt
                print(f"  ⏳ 网络抖动,{wait}s 后重试 (尝试 {attempt + 1}/{max_retries})", file=sys.stderr)
                __import__("time").sleep(wait)
                continue
            break
        raise RuntimeError(f"mysql failed: {last_err}")

    def columns(self, table: str) -> list[str]:
        out = self.exec(f"DESCRIBE `{table}`")
        return [line.split("\t")[0] for line in out.strip().splitlines() if line]

    def dump_rows(self, table: str, where: str) -> list[dict[str, Any]]:
        cols = self.columns(table)
        if not cols:
            return []
        json_pairs = ", ".join(f"'{c}', `{c}`" for c in cols)
        sql = (
            f"SELECT COALESCE("
            f"  JSON_ARRAYAGG(JSON_OBJECT({json_pairs})),"
            f"  JSON_ARRAY()"
            f") FROM `{table}` WHERE {where}"
        )
        out = self.exec(sql).strip()
        if not out or out == "NULL":
            return []
        # --raw 模式下 mysql 不再做转义,JSON_OBJECT 的输出本身就是合法 JSON,直接 parse
        try:
            return json.loads(out)
        except json.JSONDecodeError as e:
            print(f"  ⚠️ JSON parse error on {table} ({e}),返回空列表", file=sys.stderr)
            print(f"     raw output preview: {out[:200]}...", file=sys.stderr)
            return []

    def dump_one(self, table: str, where: str) -> dict[str, Any] | None:
        rows = self.dump_rows(table, where)
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
    parser.add_argument("--host", help="MySQL host(默认从 application-local.yml 读)")
    parser.add_argument("--port", help="MySQL port(默认从 application-local.yml 读)")
    parser.add_argument("--user", help="MySQL user(默认从 application-local.yml 读)")
    parser.add_argument("--password", help="MySQL password(默认从 application-local.yml 读)")
    parser.add_argument("--db", help="MySQL database name(默认从 application-local.yml 读)")
    args = parser.parse_args()

    # 命令行参数 > application-local.yml > 报错
    auto = parse_local_yml()
    cfg = {
        "host": args.host or auto.get("host"),
        "port": args.port or auto.get("port"),
        "user": args.user or auto.get("user"),
        "password": args.password if args.password is not None else auto.get("password"),
        "db": args.db or auto.get("db"),
    }
    missing = [k for k, v in cfg.items() if not v]
    if missing:
        print(f"❌ 缺少 MySQL 连接配置: {missing}", file=sys.stderr)
        print(f"   要么显式传 --host / --port / --user / --password / --db,", file=sys.stderr)
        print(f"   要么在 {LOCAL_YML} 里配 spring.datasource.* 后再跑。", file=sys.stderr)
        return 1

    print(f"🎬 导出 topic={args.topic_id} 的完整流水线为 demo fixtures...")
    print(f"   连接 {cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['db']}")
    print()

    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    db = DB(cfg["host"], cfg["port"], cfg["user"], cfg["password"], cfg["db"])

    # ── 1. Topic + 关联 script ─────────────────────────────
    topic = db.dump_one("topic", f"id={args.topic_id}")
    if not topic:
        print(f"❌ topic id={args.topic_id} 不存在,退出。先跑一条完整流水线后再来。")
        return 1
    save_json("topic.json", topic)

    scripts = db.dump_rows("script", f"topic_id={args.topic_id}")
    if not scripts:
        print(f"❌ topic={args.topic_id} 没有关联 script。先生成脚本再来。")
        return 1
    script = scripts[0]  # 取最新那条
    script_id = script["id"]
    save_json("script.json", script)
    save_json("script-sections.json", db.dump_rows("script_section", f"script_id={script_id}"))

    print(f"   ✓ topic + script (id={script_id})")

    # ── 2. Brainstorm 候选 ────────────────────────────────
    # topic 表没有 brainstorm_batch_id 字段;聚合策略改为:取选题池里所有 DRAFT
    # 状态 + AI_BRAINSTORM 来源的 topic,作为"刚生成的脑暴候选"展示;
    # 再把当前 topic 拼进去(用户实际选中那条),整体当成 brainstorm 响应。
    drafts = db.dump_rows(
        "topic",
        "status='DRAFT' AND source='AI_BRAINSTORM' ORDER BY id DESC LIMIT 8",
    )
    if not any(t["id"] == args.topic_id for t in drafts):
        drafts.append(topic)
    save_json("brainstorm-result.json", drafts)
    print(f"   ✓ brainstorm 候选 × {len(drafts)}")

    # ── 3. Storyboard ─────────────────────────────────────
    shots = db.dump_rows("storyboard_shot", f"script_id={script_id}")
    save_json("storyboard.json", shots)
    print(f"   ✓ storyboard shots × {len(shots)}")

    # ── 4. Image / Voice / Video / Cover ──────────────────
    # 这些资源都在 TOS 上(公开可访问),demo 站直接引用 TOS URL,不下载到本地。
    # 好处:demo 部署轻、git repo 不带大二进制。代价:依赖 TOS bucket 长期可用。
    images = db.dump_rows(
        "image_asset",
        f"shot_id IN (SELECT id FROM storyboard_shot WHERE script_id={script_id})",
    )
    save_json("images.json", images)

    voices = db.dump_rows("voice_asset", f"script_id={script_id}")
    save_json("voice.json", voices)

    videos = db.dump_rows("video_asset", f"script_id={script_id}")
    save_json("videos.json", videos)

    covers = db.dump_rows("cover_asset", f"script_id={script_id}")
    save_json("covers.json", covers)

    print(f"   ✓ images × {len(images)} (TOS URL,无本地拷贝)")
    print(f"   ✓ voice × {len(voices)} (TOS URL,无本地拷贝)")
    print(f"   ✓ videos × {len(videos)} (TOS URL,无本地拷贝)")
    print(f"   ✓ covers × {len(covers)} (TOS URL,无本地拷贝)")

    # ── 5. Preset / DirectorNote / Critic / FactCheck ─────
    if topic.get("preset_id"):
        preset = db.dump_one("preset", f"id={topic['preset_id']}")
        save_json("preset.json", preset)

    save_json("director-note-addendum.json", db.dump_rows(
        "director_note_addendum", f"topic_id={args.topic_id}"))
    save_json("critic-logs.json", db.dump_rows(
        "critic_log", f"script_id={script_id}"))
    save_json("factcheck-issues.json", db.dump_rows(
        "fact_check_issue", f"script_id={script_id}"))

    # ── 6. BGM ────────────────────────────────────────────
    save_json("bgm-choice.json", db.dump_one("script_bgm_choice", f"script_id={script_id}"))
    save_json("bgm-tracks.json", db.dump_rows("bgm_track", "1=1 LIMIT 20"))

    # ── 7. Pipeline runs ──────────────────────────────────
    save_json("pipeline-runs.json", db.dump_rows(
        "pipeline_run", f"topic_id={args.topic_id} OR script_id={script_id}"))

    # ── 8. Insights / 数据复盘(全局,不绑 topic) ─────────
    save_json("published-videos.json", db.dump_rows(
        "published_video", "1=1 ORDER BY published_at DESC LIMIT 50"))
    save_json("genre-stats.json", db.dump_rows("genre_stat_snapshot", "1=1 LIMIT 100"))
    save_json("weekly-reviews.json", db.dump_rows(
        "weekly_review", "1=1 ORDER BY period_end DESC LIMIT 8"))
    save_json("series.json", db.dump_rows("series", "1=1"))
    save_json("series-hooks.json", db.dump_rows("series_hook", "1=1 LIMIT 30"))

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
        "assetSource": "TOS direct URL (no local copy)",
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
    """Snake_case → camelCase 转换后再写。Spring Boot Jackson 默认按 camelCase 序列化,
    前端 TS 类型也是 camelCase,fixture 必须对齐。"""
    path = FIXTURES_DIR / name
    converted = to_camel_case(data)
    with path.open("w", encoding="utf-8") as f:
        json.dump(converted, f, ensure_ascii=False, indent=2)


def to_camel_case(obj: Any) -> Any:
    """递归把 dict 的 key 从 snake_case 转 camelCase。list 里的元素也递归处理。
    string/number/null/bool 原样返回。"""
    if isinstance(obj, dict):
        return {snake_to_camel(k): to_camel_case(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [to_camel_case(x) for x in obj]
    return obj


def snake_to_camel(s: str) -> str:
    """foo_bar_baz → fooBarBaz;已经是 camelCase 的原样返回。"""
    if "_" not in s:
        return s
    parts = s.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


if __name__ == "__main__":
    sys.exit(main())
