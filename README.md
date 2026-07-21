# daily-coding-practice

日常后端代码刷题仓库，由 WorkBuddy 自动化每日生成一道后端场景面试完整解决方案。

## 仓库说明

1. `source-doc`：手动放入的 Markdown（.md）后端面试题库，自动化每日读取抽题
2. `daily-task`：每日独立刷题文件夹，**一题一个目录**，包含原题、架构分析、可运行 Java 代码、线上踩坑总结
3. `weekly-summary`：每周自动汇总本周所有面试知识点
4. `script`：自动化 Git 提交脚本

## 生成规则

每日自动抽取一道分布式 / 中间件 / MySQL / 高并发面试题，产出完整落地代码方案，持续更新面试实战案例。

## 目录结构

```
daily-coding-practice/
├── source-doc/                # 手动放入的 Markdown 题库（.md），自动化读取抽题
├── daily-task/                # WorkBuddy 自动生成的每日题目目录
│   └── 2026-07-21/            # 每日独立文件夹（一天一题）
│       ├── question.md         # 原题原文
│       ├── analysis.md        # 考点、整体架构设计
│       ├── code/
│       │   └── Demo.java      # 完整可运行 Java 代码
│       └── summary.md         # 踩坑点、拓展方案
├── weekly-summary/            # 每周自动汇总文档
├── script/
│   └── auto_git.sh            # 自动提交推送脚本
└── README.md
```

## 本地使用

把你的后端面试题库以 `.md` 文档放进 `source-doc/`（格式见该目录下的 `格式说明.md`），每日 21:00 自动化任务会自动读取、随机抽一道未生成过的题，生成四件套并提交推送。

如需手动提交当日题目：

```bash
bash script/auto_git.sh
```
