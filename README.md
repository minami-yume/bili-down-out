# 本 fork 的修改

本 fork 基于上游 BiliDownOut，保留原有缓存读取和导出流程，并增加以下功能与兼容处理：

- 增加对 PiliPlus（`com.example.piliplus`）的支持，包括其 `files/download` 缓存目录和应用图标。
- 增加零宽空格（U+200B）路径访问选项，用于在支持该行为的系统上尝试访问 `Android/data`；也保留系统文件夹授权与 Shizuku 访问方式。
- 兼容新版官方客户端的 `entry.json` 结构，避免缺少或变化的字段导致缓存列表解析失败。
- 支持 MP3 音频导出、单视频导出以及当前客户端全部已完成视频的批量导出。缓存中存在 `cover.jpg` 时会将封面嵌入 MP3，并从 `entry.json` 的 `owner_name` 设置歌手信息。
- 支持在受支持客户端之间复制缓存目录。迁移前可从目标客户端选择一条原生下载的视频作为 `entry.json` 模板；转换时按模板的字段结构填入源视频中对应字段，并为目标模板包含但源文件缺失的 BV 号尝试根据 AV 号推导。单视频迁移和全部迁移均使用此流程。
- 迁移任务支持排队和进度显示，保留源缓存，并在开始前提醒目标客户端可能无法识别迁移结果。客户端版本或缓存实现不同，仍可能影响迁移后的识别。
- 支持删除单个视频的整组缓存（包括所有分 P），删除前会二次确认。

缓存目录访问能力由 Android 版本、设备系统和用户授权共同决定；零宽路径并非所有设备都支持。

---

<p align="center">
<img width="100px" src="https://10miaomiao.github.io/icon/bili-down-out.png"/>
</p>

<div align="center">

# BiliDownOut（哔哩缓存导出）
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/10miaomiao/bili-down-out)](https://github.com/10miaomiao/bili-down-out/releases)  ![GitHub All Releases](https://img.shields.io/github/downloads/10miaomiao/bili-down-out/total) ![GitHub stars](https://img.shields.io/github/stars/10miaomiao/bili-down-out?style=flat) ![GitHub forks](https://img.shields.io/github/forks/10miaomiao/bili-down-out)

</div>


### 关于本项目
BiliDownOut(哔哩缓存导出)是一个用于导出哔哩哔哩APP离线缓存视频的工具。

高版本安卓(13+)需要使用[Shizuku](https://shizuku.rikka.app/)授权访问Andoird/data文件夹。

主要功能：
- 导出哔哩哔哩APP离线缓存视频
- 无其它功能

### 声明
此项目 (BiliDownOut) 是个人为了兴趣而开发, 仅用于学习和测试。

### 下载
1. 从[GithubRelease](https://github.com/10miaomiao/bili-down-out/releases)下载
2. 从[GiteeRelease](https://gitee.com/10miaomiao/bili-down-out/releases)下载

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-zh-hans.png"
    alt="下载应用请到 F-Droid"
    height="80">](https://f-droid.org/packages/cn.a10miaomiao.bilidown)
