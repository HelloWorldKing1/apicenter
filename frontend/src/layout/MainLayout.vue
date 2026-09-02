<template>
  <el-container class="layout">
    <!-- 侧边导航(原型侧边栏 7 项) -->
    <el-aside width="200px" class="aside">
      <div class="logo">API 中心</div>
      <el-menu :default-active="$route.path" router background-color="#1f2739" text-color="#aeb6c7"
               active-text-color="#ffffff" class="menu">
        <el-menu-item index="/dashboard">📊 概览</el-menu-item>
        <el-menu-item index="/apps">🏢 应用管理</el-menu-item>
        <el-menu-item index="/groups">🗂️ 分组管理</el-menu-item>
        <el-menu-item index="/interfaces">🔌 接口管理</el-menu-item>
        <el-menu-item index="/adapters">🧩 适配器</el-menu-item>
        <el-menu-item index="/monitor">📈 接口监控</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶部面包屑 -->
      <el-header class="header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>管理面</el-breadcrumb-item>
          <el-breadcrumb-item>{{ $route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
// 主布局:侧边导航 + 面包屑 + 页面出口
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: #1f2739; }
.logo {
  color: #fff; font-size: 18px; font-weight: 600;
  padding: 18px 20px; letter-spacing: 1px;
}
.menu { border-right: none; }
.header {
  background: #fff; border-bottom: 1px solid #e8eaf0;
  display: flex; align-items: center;
}
.main { padding: 20px; }
</style>
