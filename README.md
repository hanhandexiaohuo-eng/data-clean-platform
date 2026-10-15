# DataClean - 数据导入、预处理清洗与可视化分析平台

一个功能完整的数据处理与智能分析平台，支持数据导入、清洗规则配置、可视化分析，并集成 AI 平行补充说明模块。

## 技术栈

### 后端
- Java 17
- Spring Boot 3.2.x
- MyBatis-Plus 3.5.x
- MySQL 8.0
- Apache POI (Excel解析)
- OpenCSV (CSV解析)
- OkHttp3 (AI API调用)
- Maven 3.9+

### 前端
- Vue 3 (Composition API)
- Element Plus 2.x
- ECharts 5.x
- Axios
- marked (Markdown渲染)
- Vue Router 4
- Pinia (状态管理)

## 项目结构

```
data-clean-platform/
├── backend/                    # Spring Boot 后端项目
│   ├── src/main/java/com/dataclean/
│   │   ├── DataCleanApplication.java
│   │   ├── controller/         # RESTful API 控制器
│   │   ├── service/            # 业务逻辑层
│   │   ├── mapper/             # MyBatis-Plus 数据访问层
│   │   ├── entity/             # 实体类
│   │   ├── dto/                # 数据传输对象
│   │   ├── config/             # 配置类
│   │   ├── util/               # 工具类
│   │   ├── handler/            # 类型处理器
│   │   └── exception/          # 全局异常处理
│   └── src/main/resources/
│       ├── application.yml
│       └── mapper/             # MyBatis XML 映射文件
├── frontend/                   # Vue 3 前端项目
│   ├── src/
│   │   ├── components/         # 公共组件
│   │   │   ├── charts/         # ECharts 图表组件
│   │   │   ├── layout/         # 布局组件
│   │   │   └── ai/             # AI 相关组件
│   │   ├── views/              # 页面视图
│   │   ├── router/             # 路由配置
│   │   ├── api/                # API 接口封装
│   │   ├── utils/              # 工具函数
│   │   └── stores/             # Pinia 状态管理
│   ├── package.json
│   └── vite.config.js
└── sql/                        # 数据库初始化脚本
    └── init.sql
```

> 📌 **关于 `backend/src/main/resources/static/index.html`**
> 该文件是一个**单文件原型 Demo**，其页面数据、清洗结果与 AI 分析结果均为**内嵌的静态演示数据**，不请求后端接口，**不代表平台的实际功能**。实际可用的前端位于 `frontend/` 目录（Vue 3 SPA）。

## 环境要求

- JDK 17+
- MySQL 8.0+
- Node.js 18+
- Maven 3.9+

## 快速启动

### 1. 初始化数据库

```bash
# 登录 MySQL
mysql -u root -p

# 执行初始化脚本
source sql/init.sql
```

### 2. 启动后端

```bash
cd backend

# 1. 从模板复制配置文件（application.yml 已被 .gitignore 忽略，仓库中不包含）
cp src/main/resources/application.yml.example src/main/resources/application.yml

# 2. 修改 application.yml 中的数据库连接信息，根据你的 MySQL 配置修改以下字段：
# spring.datasource.url
# spring.datasource.username
# spring.datasource.password
# jwt.secret  （请替换为随机字符串，长度不少于 32 位）
#
# 也可改用环境变量注入，见项目根目录 .env.example

# 3. 编译并运行
mvn clean install
mvn spring-boot:run

# 后端服务将在 http://localhost:8080 启动
```

> ⚠️ **注意**：`application.yml` 包含数据库密码与 JWT 密钥，已被 `.gitignore` 排除，**请勿提交到仓库**。
> 仓库中仅提供脱敏模板 `application.yml.example`。

### 3. 启动前端

```bash
cd frontend

npm install
npm run dev

# 前端服务将在 http://localhost:5173 启动
```

## AI 模块配置说明

### 1. 配置大模型 API

进入系统后，访问 **AI配置管理** 页面，配置以下信息：

- **API 地址**: 大模型接口地址
  - OpenAI: `https://api.openai.com/v1/chat/completions`
  - 阿里云通义千问: `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
  - 本地 Ollama: `http://localhost:11434/v1/chat/completions`
  
- **API Key**: 你的 API 密钥
- **模型名称**: 
  - `gpt-3.5-turbo`
  - `gpt-4`
  - `qwen-turbo`
  - `qwen-plus`
  - `llama2` (本地)

### 2. Prompt 模板管理

系统内置以下模板类型：
- **数据集摘要**: 自动生成数据概览、字段分布、质量评估
- **字段关系解读**: 分析字段间的相关性和依赖关系
- **异常值分析**: 识别异常模式并提供处理建议
- **图表洞察**: 解读图表趋势和关键发现
- **清洗方案建议**: 基于数据特征推荐清洗策略

### 3. 数据安全说明

AI 模块**不会**将原始数据行发送到外部 API，仅传输：
- 数据集元信息（表名、列名、数据类型）
- 统计摘要（均值、中位数、标准差、分位数等聚合值）
- 用户选择的模板和自定义指令

## API 接口文档

启动后端后，访问 `http://localhost:8080/swagger-ui.html` 查看完整 API 文档。

### 核心接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/dataset | GET/POST/DELETE | 数据集管理 |
| /api/dataset/{id}/upload | POST | 文件上传 |
| /api/dataset/{id}/preview | GET | 数据预览 |
| /api/dataset/{id}/columns | GET | 获取列信息 |
| /api/clean/rule | GET/POST/PUT/DELETE | 清洗规则管理 |
| /api/clean/execute | POST | 执行清洗 |
| /api/clean/log | GET | 清洗日志查询 |
| /api/clean/rollback/{logId} | POST | 回滚清洗 |
| /api/analysis/chart | POST | 生成图表数据 |
| /api/analysis/statistics | GET | 获取统计信息 |
| /api/ai/generate | POST | AI 生成（非流式）|
| /api/ai/generate/stream | GET | AI 流式生成（SSE）|
| /api/ai/config | GET/PUT | AI 配置管理 |
| /api/ai/template | GET/POST/PUT/DELETE | Prompt 模板管理 |
| /api/ai/history | GET | AI 生成历史 |

## 核心功能说明

### 数据导入
- 支持 CSV 和 Excel (.xlsx/.xls) 文件上传
- 自动解析列名和数据类型
- 上传后自动创建 MySQL 数据表
- 支持在线预览前 20 行数据

### 数据清洗
- 可配置规则引擎，支持拖拽排序
- 缺失值处理（删除行 / 均值填充 / 中位数填充 / 固定值填充）
- 重复行删除
- 数据类型转换
- 列名标准化（去除空格、特殊字符）
- 异常值处理（Z-Score / IQR 方法）
- 自定义表达式过滤
- 每次清洗生成日志，支持一键回滚

### 可视化分析
- 支持柱状图、折线图、饼图、散点图、热力图
- 拖拽配置 X 轴、Y 轴、分组字段
- 聚合方式：SUM、AVG、COUNT、MAX、MIN
- 自动计算统计量（均值、方差、最大最小值、分位数）
- 图表导出为图片，数据导出为 CSV

### AI 分析
- 支持 SSE 流式输出
- Markdown 渲染 AI 回复
- 内置多种分析模板
- 生成历史记录
- 数据安全保障（不传输原始数据）

## 开发规范

- 遵循阿里巴巴 Java 开发规范
- 代码注释使用中文
- RESTful API 统一返回格式
- 前端组件使用 Composition API
- 统一异常处理和日志记录

## 敏感信息与忽略规则

本仓库已配置 `.gitignore`，以下内容**不会也不应**被提交：

- `application.yml` —— 含数据库密码与 JWT 密钥，仓库中仅保留脱敏模板 `application.yml.example`
- `.env` —— 仓库中仅保留模板 `.env.example`
- `node_modules/`、`dist/`、`target/` —— 依赖与构建产物
- `.idea/` 等 IDE 个人配置
- `uploads/` 与 `*.log` —— 运行时数据与日志

首次启动前请从模板复制配置文件：

```bash
cp backend/src/main/resources/application.yml.example backend/src/main/resources/application.yml
```

> 关于 `backend/DownloadWrapper.java` 与 `backend/DownloadJdk21.java`：
> 二者是项目搭建阶段用于下载 Maven Wrapper 与 JDK 21 的**一次性辅助脚本**
> （下载源为 Maven 官方仓库与 Adoptium 官方发布页，不含任何凭据）。
> 保留仅为记录环境搭建过程，可通过 `mvnw.cmd` 与系统 JDK 替代。

## 许可证

MIT License
