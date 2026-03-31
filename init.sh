#!/bin/bash
# ==========================================
#  MobileClaw - Agent 环境初始化脚本
# ==========================================
# 用途：每个 Agent 会话开始时运行，快速恢复开发环境
# 用法：bash init.sh

set -e

echo "=========================================="
echo "  MobileClaw - Agent 环境初始化"
echo "=========================================="

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo ""
echo "[1/6] 当前目录: $PROJECT_DIR"

echo ""
echo "[2/6] Git 状态:"
git status --short || echo "  (无 git 变更)"

echo ""
echo "[3/6] 最近 10 条 commit:"
git log --oneline -10 || echo "  (无 commit 历史)"

echo ""
echo "[4/6] 功能完成进度:"
if [ -f "feature_list.json" ]; then
  TOTAL=$(python3 -c "import json; f=open('feature_list.json'); d=json.load(f); print(len(d['features']))" 2>/dev/null || echo "?")
  PASSED=$(python3 -c "import json; f=open('feature_list.json'); d=json.load(f); print(sum(1 for x in d['features'] if x['passes']))" 2>/dev/null || echo "0")
  echo "  总计: $TOTAL 个功能"
  echo "  已完成: $PASSED 个"
  echo "  剩余: $((TOTAL - PASSED)) 个"

  echo ""
  echo "  未完成的功能:"
  python3 -c "
import json
with open('feature_list.json') as f:
    d = json.load(f)
for feat in d['features']:
    if not feat['passes']:
        print(f\"    [{feat['id']}] P{feat['priority']}: {feat['description']}\")
" 2>/dev/null || echo "    (解析失败)"
else
  echo "  (feature_list.json 不存在)"
fi

echo ""
echo "[5/6] 依赖检查:"
if [ -d "node_modules" ]; then
  PKG_COUNT=$(ls node_modules | wc -l | tr -d ' ')
  echo "  node_modules 已存在 ($PKG_COUNT 个包)"
else
  echo "  node_modules 不存在，运行 pnpm install"
fi

echo ""
echo "[6/6] OpenClaw 连接检查:"
if command -v lsof &> /dev/null; then
  if lsof -i :18789 &> /dev/null; then
    echo "  openclaw gateway 端口 18789 正在监听 ✓"
  else
    echo "  openclaw gateway 端口 18789 未检测到（可能未启动或端口不同）"
  fi
else
  echo "  (跳过：lsof 不可用)"
fi

echo ""
echo "=========================================="
echo "  初始化完成"
echo "=========================================="
echo ""
echo "下一步操作:"
echo "  1. 阅读 progress.txt 了解已完成的工作"
echo "  2. 阅读 feature_list.json 找到下一个未完成的功能"
echo "  3. 完成功能后更新 feature_list.json 中的 passes 字段"
echo "  4. git commit 提交更改"
echo "  5. 更新 progress.txt 记录本次会话的工作"
echo ""
