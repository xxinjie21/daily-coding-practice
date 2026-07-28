# daily-coding-practice

日常后端代码刷题仓库，每日生成一道后端场景面试完整解决方案。

## 仓库说明

1. `source-doc`：手动放入的 Markdown（.md）后端面试题库，自动化每日读取抽题
2. `daily-task`：每日独立刷题文件夹，**一题一个目录**，每题一份 `题解.md`（一句话题目 + 生活比喻 + 怎么做 + 一句提醒）加可运行 Java 代码 `code/Demo.java`
3. `weekly-summary`：每周自动汇总本周所有面试知识点
4. `script`：自动化 Git 提交脚本

## 生成规则

每日自动抽取一道分布式 / 中间件 / MySQL / 高并发面试题，产出完整落地代码方案，持续更新面试实战案例。

## 目录结构

```
daily-coding-practice/
├── source-doc/                # 手动放入的 Markdown 题库（.md），自动化读取抽题
├── daily-task/                # WorkBuddy 自动生成的每日题目目录
│   └── 2026-07-21-负载均衡器/  # 每日独立文件夹（一天一题，目录名带题目主题）
│       ├── 题解.md             # 单文档：一句话题目 + 生活比喻 + 怎么做 + 一句提醒
│       └── code/
│           └── Demo.java      # 完整可运行 Java 代码
├── weekly-summary/            # 每周自动汇总文档
├── script/
│   └── auto_git.sh            # 自动提交推送脚本
└── README.md
```

## 本地使用

把你的后端面试题库以 `.md` 文档放进 `source-doc/`（格式见该目录下的 `格式说明.md`），每日 21:00 自动化任务会自动读取、随机抽一道未生成过的题，生成 `题解.md` + `code/Demo.java`（题解 + 代码两件）并提交推送。

如需手动提交当日题目：

```bash
bash script/auto_git.sh
```
