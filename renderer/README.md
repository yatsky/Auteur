# auteur-renderer

auteur 视频合成器,基于 [Remotion](https://www.remotion.dev/)。
backend 通过 `npx remotion render` 调用,接 `BIOGRAPHY_1P` 第一视角生平流水线。

## 安装

```bash
cd renderer
npm install
```

首次 `npm run dev` 或 `npm run render` 时 Remotion 会自动下载 Chromium (~150MB)。

## 开发预览

```bash
npm run dev
```

打开 Remotion Studio,左侧选 `Biography`,右侧时间轴拖拽预览。改组件代码热重载。

## 渲染样例视频

```bash
npm run render
```

读 `src/sample-props.json`,渲出 `out/biography.mp4`。样例用 picsum.photos 占位图,无配音。

快渲版(降低 jpeg 质量提速):

```bash
npm run render:fast
```

## 模板结构

```
src/
├── index.ts                    # registerRoot 入口
├── Root.tsx                    # 注册 Biography composition + defaultProps
├── types.ts                    # zod schema 定义 props 契约
├── BiographyComposition.tsx    # 主模板:intro → shots → outro
├── components/
│   ├── PersonaIntro.tsx        # 主角基准图 + 红色印章自称 + 身份卡
│   ├── KenBurnsImage.tsx       # 静态图运镜(替代图生视频的核心)
│   ├── Subtitle.tsx            # 底部字幕,逐字显现
│   ├── ArcChapter.tsx          # arcNode 年份章节卡
│   └── Outro.tsx               # 片尾关注引导
└── sample-props.json           # 测试用 props
```

## 给 backend 调用

backend 加 `RemotionVideoRenderer.java` 实现 `VideoRenderer`,内部:

```java
ProcessBuilder pb = new ProcessBuilder(
    "npx", "remotion", "render",
    "src/index.ts",
    "Biography",
    outputPath,
    "--props=" + propsJsonPath,
    "--concurrency=4"
);
pb.directory(new File("/path/to/auteur/renderer"));
pb.inheritIO();
Process p = pb.start();
int exitCode = p.waitFor();
```

`propsJsonPath` 是 backend 把 `topic.persona_json` + 分镜 + 音频路径序列化出来的临时 JSON 文件。

## Props 契约

见 `src/types.ts` 的 `biographyPropsSchema`。关键字段:

| 字段 | 来源 |
|---|---|
| `personaName` / `personaSelfRef` / `personaIdentity` | `topic.persona_json` |
| `protagonistRefUrl` | `topic.protagonist_ref_url` |
| `audioUrl` / `audioDurationSec` | TTS 输出 |
| `shots[].imageUrl` | 整片生图结果 |
| `shots[].arcYear` / `arcEvent` | `persona.arcNodes` 命中节点 |
| `shots[].startSec` / `durationSec` | `ShotTimingResolver` 输出 |

## 性能预期

- 1080×1920 / 30fps / 5 分钟视频
- M 系列 Mac 本机:**约 3-8 分钟**渲一条
- 加 `--concurrency=4` 充分利用多核

## TODO(后续迭代)

- [ ] 接 `@remotion/google-fonts` 替代系统字体,保证跨平台一致
- [ ] 加 `MysteryComposition`(悬案号第三人称模板)
- [ ] 加水墨晕染转场(`@remotion/transitions`)
- [ ] 加 BGM 轨(`<Audio src=bgm volume={0.2} />`)
- [ ] arcChapter 接 spring 弹簧动画完整化
- [ ] 字幕用 Whisper SRT 时间轴(逐句精准对齐)
