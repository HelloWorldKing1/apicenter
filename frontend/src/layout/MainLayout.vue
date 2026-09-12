<template>
  <el-container class="layout">
    <!-- 侧边导航（视觉与 API中心原型.html 侧边栏对齐：200px / #1d2129 / 圆点 logo / 几何图标 / 底部脚注） -->
    <el-aside width="200px" class="aside">
      <div class="logo"><span class="logo-dot"></span>API 中心</div>
      <el-menu :default-active="$route.path" router class="menu">
        <el-menu-item index="/dashboard"><span class="ico">◧</span>概览</el-menu-item>
        <el-menu-item index="/apps"><span class="ico">▤</span>应用管理</el-menu-item>
        <el-menu-item index="/groups"><span class="ico">▦</span>分组管理</el-menu-item>
        <el-menu-item index="/interfaces"><span class="ico">⇄</span>接口管理</el-menu-item>
        <el-menu-item index="/monitor"><span class="ico">◎</span>接口监控</el-menu-item>
        <el-menu-item index="/adapters"><span class="ico">⚙</span>适配器</el-menu-item>
      </el-menu>
      <div class="foot">API 中心 · 管理控制台</div>
    </el-aside>

    <el-container>
      <!-- 顶部栏（原型 .topbar：56px / 白底 / 下边框 / 面包屑 + 标题） -->
      <el-header class="header">
        <span class="crumb">管理面 / </span>
        <span class="title">{{ $route.meta.title }}</span>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
// 主布局：侧边导航 + 顶部栏 + 页面出口
// 视觉口径来自 doc/API中心原型.html（侧边栏 #1d2129 / 主色 #2f54eb / 页面内边距 24px）
</script>

<style scoped>
.layout { height: 100%; }

/* ---------- 侧边栏 ---------- */
.aside {
  background: #1d2129;
  display: flex;
  flex-direction: column;
}
.logo {
  display: flex; align-items: center; gap: 8px;
  height: 56px; padding: 0 20px; flex: none;
  color: #fff; font-size: 16px; font-weight: 700; letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, .08);
}
.logo-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: #2f54eb; flex: none;
}
.menu {
  border-right: none; background: transparent;
  padding: 12px 8px;
  flex: 1;
}
/* 菜单项：原型 .menu a（gap 10 / padding 10px 14px / radius 6 / 主色高亮） */
.menu :deep(.el-menu-item) {
  height: 40px; line-height: 40px;
  padding: 0 14px !important;
  margin-bottom: 4px;
  border-radius: 6px;
  color: #c9cdd4;
  font-size: 14px;
}
.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, .06);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: #2f54eb;
  color: #fff;
}
.ico {
  width: 16px; text-align: center; font-size: 14px;
  margin-right: 10px; display: inline-block;
}
.foot {
  margin-top: auto; padding: 16px 20px;
  font-size: 12px; color: #5f6672;
}

/* ---------- 顶部栏 ---------- */
.header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e6e8eb;
  display: flex; align-items: center; gap: 8px;
  padding: 0 24px;
  position: sticky; top: 0; z-index: 10;
}
.crumb { color: #86909c; }
.title { font-size: 16px; font-weight: 600; color: #1f2329; }

/* ---------- 内容区（原型 .page{padding:24px}） ---------- */
.main { padding: 24px; }
</style>
