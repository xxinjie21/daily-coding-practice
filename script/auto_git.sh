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

# 从 题解.md 首行提取题目标题（去掉开头的 # 号），用于提交信息一目了然
# 兼容旧格式：没有 题解.md 时回退读 question.md
if [ -f "$TARGET_DIR/题解.md" ]; then
    TITLE=$(head -n 1 "$TARGET_DIR/题解.md" | sed 's/^#* *//')
elif [ -f "$TARGET_DIR/question.md" ]; then
    TITLE=$(head -n 1 "$TARGET_DIR/question.md" | sed 's/^#* *//')
else
    TITLE="后端面试题完整实现"
fi

# 提交当日刷题目录
git add "$TARGET_DIR" source-doc/
if git diff --cached --quiet; then
    # 没有暂存内容时跳过 commit，避免 "nothing to commit" 报错干扰后续判断
    echo "暂无可提交的改动（今日内容可能已经提交过）"
else
    git commit -m "daily-coding：$TODAY $TITLE"
fi

# 推送：必须显式指定「只使用 store 凭据助手」并关闭一切交互式提示。
# 原因：本机同时存在 store 与 Git for Windows 的凭据选择器（git-credential-helper-selector），
# 无人值守运行时它会弹出选择菜单等待输入，导致 push 永久挂起（不会报错，也不会超时）。
# 凭据已保存在 ~/.git-credentials，store 助手可直接读取，无需任何人工输入。
if GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never \
   timeout 180 git -c credential.helper= -c credential.helper=store push origin main; then
    echo "已完成自动推送：$TARGET_DIR（$TITLE）"
else
    echo "自动推送失败（退出码 $?）：本地提交已生成，请人工检查网络与凭据后手动推送" >&2
    exit 1
fi
