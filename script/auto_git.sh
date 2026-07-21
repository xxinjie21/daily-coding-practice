#!/bin/bash
# 配置你的 github 账号
git config --global user.name "xxinjie21"
git config --global user.email "xxinjie21@163.com"

# 定位仓库根目录
BASE_DIR=$(dirname "$0")/..
cd "$BASE_DIR" || exit 1

# 获取当日日期
TODAY=$(date +%Y-%m-%d)
TARGET_DIR="daily-task/$TODAY"

# 判断今日文件夹是否生成，无则跳过提交
if [ ! -d "$TARGET_DIR" ]; then
    echo "今日刷题文件夹不存在，无需提交"
    exit 0
fi

# 提交当日刷题目录
git add "$TARGET_DIR" source-doc/
git commit -m "daily-coding：$TODAY 后端面试题完整实现"
git push origin main
echo "已完成自动推送：$TARGET_DIR"
