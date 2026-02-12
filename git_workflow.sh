#!/bin/bash

# --- Git 程序员标准工作流 ---

# 1. 查看当前状态
# 确认 modified 列表是否符合预期
git status

# 2. 对比改动细节 (Optional)
# 如果想在脚本里看改动，取消下面行的注释
# git diff

# 3. 将所有改动加入暂存区
# 你也可以手动执行 git add <filename> 
git add .

# 4. 提交改动
# 这一步会直接调用你的默认编辑器 Vim
# 请在打开的 Vim 窗口中输入有意义的 commit message
git commit -m "commit message"

# 5. 推送到远程分支
# 默认推送到 origin 的 main 分支
git push origin main

echo "Git 工作流执行完毕！"
