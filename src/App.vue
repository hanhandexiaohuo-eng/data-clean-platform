<template>
  <router-view />
</template>

<script setup>
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #2c3e50;
  background-color: #f0f2f5;
}

/* 全局滚动条样式 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 3px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

/* Markdown 渲染样式 */
.markdown-body {
  line-height: 1.8;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 16px 0 12px;
  font-weight: 600;
  color: #1a1a2e;
}

.markdown-body h1 {
  font-size: 20px;
  border-bottom: 2px solid #e4e7ed;
  padding-bottom: 8px;
}

.markdown-body h2 {
  font-size: 18px;
}

.markdown-body h3 {
  font-size: 16px;
}

.markdown-body p {
  margin: 8px 0;
  line-height: 1.8;
}

.markdown-body ul,
.markdown-body ol {
  margin: 8px 0;
  padding-left: 24px;
}

.markdown-body li {
  margin: 4px 0;
}

.markdown-body code {
  background: #f4f4f5;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 14px;
  color: #d73a49;
}

.markdown-body pre {
  background: #1a1a2e;
  color: #e4e4e7;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
}

.markdown-body pre code {
  background: transparent;
  color: inherit;
  padding: 0;
}

.markdown-body blockquote {
  border-left: 4px solid #409eff;
  margin: 12px 0;
  padding: 8px 16px;
  background: #ecf5ff;
  border-radius: 0 8px 8px 0;
}

.markdown-body table {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid #dcdfe6;
  padding: 10px 14px;
  text-align: left;
}

.markdown-body th {
  background: #f5f7fa;
  font-weight: 600;
}

.markdown-body tr:nth-child(even) {
  background: #fafafa;
}

.markdown-body strong {
  color: #1a1a2e;
}
</style>
