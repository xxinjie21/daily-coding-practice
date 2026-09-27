# daily-coding-practice

日常后端代码刷题仓库，每日生成一道后端场景面试完整解决方案。

## 仓库说明

1. `source-doc`：手动放入的 Markdown（.md）后端面试题库，自动化每日读取抽题
2. `daily-task`：每日独立刷题文件夹，**一题一个目录**，每题一份 `题解.md`（一句话题目 + 生活比喻 + 怎么做 + 一句提醒）加一个可运行的 Maven 工程 `code/`
3. `weekly-summary`：每周自动汇总本周所有面试知识点
4. `script`：自动化 Git 提交脚本

## 生成规则

每日自动抽取一道分布式 / 中间件 / MySQL / 高并发面试题，产出完整落地代码方案，持续更新面试实战案例。

## 目录结构

```
daily-coding-practice/
├── source-doc/                # 手动放入的 Markdown 题库（.md），自动化读取抽题
├── daily-task/                # WorkBuddy 自动生成的每日题目目录
│   └── 2026-09-27-OAuth2授权与令牌/   # 每日独立文件夹（一天一题，目录名带题目主题）
│       ├── 题解.md             # 单文档：一句话题目 + 生活比喻 + 怎么做 + 一句提醒
│       └── code/
│           ├── pom.xml              # Maven 工程（Java 17 / UTF-8）
│           ├── docker-compose.yml   # 仅需中间件时生成（Redis / MySQL / Kafka 等）
│           └── src/main/java/
│               └── Demo.java        # 可运行演示代码
├── weekly-summary/            # 每周自动汇总文档
├── script/
│   └── auto_git.sh            # 自动提交推送脚本
└── README.md
```

## 代码约定

- **必须使用原生技术栈**：涉及 Redis 就写真实 Redis 命令 / 客户端 API（Jedis），涉及 MySQL 就写真实 SQL（JDBC + `EXPLAIN`），涉及消息队列就写 Kafka 原生 API。**不允许用 `ConcurrentHashMap` 之类的内存对象仿真中间件。**
- **代码精简、可读性优先**：只保留演示核心机制所必需的代码，不堆砌用不到的分支与配置；单个 `Demo.java` 尽量控制在 300 行以内，注释讲清"为什么"而不是逐行翻译代码。
- **必须能编译通过**：每个工程都要过 `mvn -o -q compile`（Java 17 / UTF-8）。依赖的中间件由该题的 `docker-compose.yml` 拉起，能起容器时再跑一遍 `exec:java` 做运行自检。

> 注：2026-09-27 已把此前的 41 个历史任务**全部回炉改造**完成（含 2026-09-27 当天），旧的单文件仿真版 `code/Demo.java` 已删除。
> 改造后 41/41 离线编译通过；需要中间件的任务都附了 `docker-compose.yml`，在 `code/` 下 `docker compose up -d && mvn -q compile exec:java` 即可运行。
> 运行期行为尚未实机验证（改造时未启动任何中间件）。

## 本地使用

把你的后端面试题库以 `.md` 文档放进 `source-doc/`（格式见该目录下的 `格式说明.md`），每日 21:00 自动化任务会自动读取、随机抽一道未生成过的题，生成 `题解.md` + `code/`（Maven 工程）并提交推送。

如需手动提交当日题目：

```bash
bash script/auto_git.sh
```
