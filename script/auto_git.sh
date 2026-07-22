#!/bin/bash
# 依赖 Git Bash 环境（需 bash 及 GNU 工具 date 等）；本地与 WorkBuddy 执行时均使用 Git Bash
# 配置本仓库的 github 账号（仅仓库级，不污染全局 git 配置）
git config user.name "xxinjie21"
git config user.email "xxinjie21@163.com"

# 定位仓库根目录
BASE_DIR=$(dirname "$0")/..
cd "$BASE_DIR" || exit 1

# 获取当日日期
TODAY=$(date +%Y-%m-%d)

# 按日期前缀定位今日刷题目录（文件夹名形如 2026-07-22-题目标述，兼容旧版纯日期命名）
shopt -s nullglob
dirs=(daily-task/${TODAY}*)
shopt -u nullglob
if [ ${#dirs[@]} -eq 0 ]; then
    echo "今日刷题文件夹不存在，无需提交"
    exit 0
fi
TARGET_DIR="${dirs[0]}"

# 从 question.md 首行提取题目标题（去掉开头的 # 号），用于提交信息一目了然
if [ -f "$TARGET_DIR/question.md" ]; then
    TITLE=$(head -n 1 "$TARGET_DIR/question.md" | sed 's/^#* *//')
else
    TITLE="后端面试题完整实现"
fi

# 提交当日刷题目录
git add "$TARGET_DIR" source-doc/
git commit -m "daily-coding：$TODAY $TITLE"
git push origin main
echo "已完成自动推送：$TARGET_DIR（$TITLE）"
