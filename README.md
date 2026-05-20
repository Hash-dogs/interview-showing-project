# 🤖 AI 智能面试辅助平台

> 基于 Spring Boot 4.0 + Java 21 + Spring AI + PostgreSQL/pgvector + Redis 构建的全栈 AI 面试辅助系统，集成简历智能分析、AI 模拟面试与 RAG 知识库检索三大核心能力。

---

## 目录

- [技术栈概览](#技术栈概览)
- [项目功能简介](#项目功能简介)
- [系统架构与核心逻辑](#系统架构与核心逻辑)
- [项目配置与本地启动](#项目配置与本地启动)
- [项目亮点与设计优势](#项目亮点与设计优势)
- [未来可改进方向](#未来可改进方向)

---

## 技术栈概览

| 层级 | 技术选型 | 说明 |
|------|----------|------|
| 后端框架 | Spring Boot 4.0 + Java 21 | 利用 Virtual Threads 提升 I/O 并发能力 |
| AI 集成 | Spring AI 2.0 + 阿里云百炼（Qwen） | 统一 AI 抽象层，支持流式输出 |
| 向量存储 | PostgreSQL 14+ + pgvector | HNSW 索引 + 余弦相似度，RAG 检索 |
| 异步队列 | Redis Stream | 简历分析与知识库向量化解耦异步处理 |
| 对象存储 | RustFS / MinIO（S3 兼容） | 简历文件、PDF 报告持久化 |
| 构建工具 | Gradle | 构建配置简洁，依赖管理清晰 |
| 前端 | React + TypeScript + Vite | 单页应用，Nginx 容器化部署 |
| 容器化 | Docker Compose | 一键编排 6 个服务，本地开发友好 |

---

## 项目功能简介

### 1. 简历智能分析

用户上传 PDF 简历后，系统通过 Redis Stream 异步投递分析任务，后台调用大语言模型对简历内容进行结构化评估，输出岗位匹配度、技能亮点与改进建议。分析状态实时可见（待分析 / 分析中 / 已完成 / 失败），完成后可一键导出 PDF 分析报告。

系统内置内容哈希去重机制，相同简历不会重复消耗 Token；失败场景自动重试最多 3 次，保障分析任务的可靠完成。

### 2. AI 模拟面试

平台内置 10+ 面试方向，涵盖 Java 后端、前端、算法、系统设计、AI Agent 。每个方向由独立的 SKILL.md 文件定义考察范围、题目难度分布及参考知识库。

**核心交互流程：**

```
选择方向 → 生成面试题（历史去重）→ 用户作答
    → 多轮智能追问 → 批量评估 → 结构化报告导出
```

- **历史去重**：出题时自动排除当前会话已出现的题目，避免重复。
- **智能追问**：每道主题问题可配置追问数量（默认 1 条），模拟真实面试的追问压力。
- **统一评估引擎**：文字面试与语音面试共享同一套评估流水线（分批评估 → 结构化输出 → 二次汇总 → 降级兜底），结果可对比。
- **时长联动**：总时长滑块调整后，自我介绍 / 技术考察 / 项目深挖 / 反问环节各阶段按比例自动分配。

### 3. RAG 知识库检索

用户可上传技术文档或笔记构建私有知识库，系统使用 `text-embedding-v3` 模型生成 1024 维向量并写入 pgvector，支持基于余弦相似度的语义检索。在模拟面试中，系统会自动从知识库中召回相关片段作为 Prompt 上下文，使生成的题目和评估更贴合用户的技术背景。

### 4. 功能结构速览

```
interview-guide/
├── app/                        # Spring Boot 后端
│   └── modules/
│       ├── resume/             # 简历上传 & 智能分析
│       ├── interview/          # 文字模拟面试
│       ├── knowledgebase/      # 知识库 RAG 管理
│       └── interviewschedule/  # 面试日程安排
│   └── infrastructure/
│       ├── redis/              # Redis Stream 生产者/消费者抽象
│       ├── storage/            # S3 对象存储封装
│       └── export/             # PDF 报告导出
└── frontend/                   # React + TypeScript 前端
```

---

## 系统架构与核心逻辑

### 异步处理流（Redis Stream）

简历分析和知识库向量化是典型的耗时 I/O 操作，系统通过 Redis Stream 将其与主请求链路解耦：

```
HTTP 请求 → 写入 Redis Stream → 立即返回 202
              ↓
        AbstractStreamConsumer（消费者组）
              ↓
        AI 分析 / 向量化
              ↓
        更新状态 + 持久化结果
```

`AbstractStreamProducer` 和 `AbstractStreamConsumer` 采用模板方法模式封装了公共逻辑（投递、消费、重试、状态更新），具体业务只需实现 `streamKey()`、`buildMessage()` 等抽象方法即可复用整套异步框架。

### RAG 检索链路

```
文档上传 → 文本分块 → text-embedding-v3 生成向量
                               ↓
                         写入 pgvector（HNSW 索引）

用户提问 → 问题向量化 → 余弦相似度检索 TopK 片段
                               ↓
                    注入 Prompt → 调用 LLM → 流式返回
```

pgvector 的 HNSW 索引在百万级向量规模下仍能保持毫秒级近似最近邻查询，余弦相似度匹配语义而非字面关键词，检索质量显著优于传统全文搜索。

### 统一 AI 评估引擎

为避免单次 Prompt 携带全部答题记录导致超出上下文窗口，评估模块采用分批评估策略（默认每批 8 道），最终通过二次汇总 Prompt 生成整体报告，并设有降级兜底逻辑保证在部分批次失败时仍能输出有效结果。

---

## 项目配置与本地启动

### 环境依赖

- Docker & Docker Compose（推荐，一键拉起所有中间件）
- JDK 21+
- Node.js 18+（前端构建）
- 阿里云百炼 API Key（[申请地址](https://bailian.console.aliyun.com/)）

### 快速启动（Docker 全量部署）

```bash
# 1. 克隆仓库
git clone https://github.com/Hash-dogs/interview-showing-project
cd interviewer

# 2. 复制并填写环境变量
cp .env.example .env
# 编辑 .env，至少填写以下必填项：
#   AI_BAILIAN_API_KEY=your_key_here
#   AI_MODEL=qwen3.5-flash        # 可选，默认 qwen3.5-flash

# 3. 一键启动（后端 + 前端 + PostgreSQL + Redis + MinIO）
docker compose up -d

# 访问前端：http://localhost:3000
# 访问后端 API：http://localhost:8080
```

Docker Compose 编排了 6 个服务：PostgreSQL（含 pgvector）、Redis、MinIO（S3 兼容存储）、MinIO Bucket 初始化、Spring Boot 后端、React 前端（Nginx）。数据通过命名卷持久化，`docker compose down` 不会丢失数据。

### 仅启动中间件（本地开发模式）

```bash
# 启动 PostgreSQL + Redis + RustFS 三个依赖
docker compose -f docker-compose.dev.yml up -d

# 本地运行后端（Gradle）
cd app
./gradlew bootRun

# 本地运行前端
cd frontend
npm install && npm run dev
```

### 关键配置项说明（application.yml）

```yaml
spring:
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${AI_BAILIAN_API_KEY}
      embedding:
        options:
          model: text-embedding-v3
    vectorstore:
      pgvector:
        index-type: HNSW               # 近似最近邻索引
        distance-type: COSINE_DISTANCE  # 余弦相似度
        dimensions: 1024               # text-embedding-v3 输出维度
        initialize-schema: true        # 开发环境自动建表

app:
  interview:
    follow-up-count: 1         # 每题追问数量
    evaluation-batch-size: 8   # 评估分批大小
```

### 环境变量速查

| 变量名 | 必填 | 默认值 | 说明 |
|--------|------|--------|------|
| `AI_BAILIAN_API_KEY` | ✅ | — | 阿里云百炼 API Key |
| `AI_MODEL` | ❌ | `qwen3.5-flash` | 使用的 LLM 模型 |
| `APP_INTERVIEW_FOLLOW_UP_COUNT` | ❌ | `1` | 每题追问数 |
| `APP_INTERVIEW_EVALUATION_BATCH_SIZE` | ❌ | `8` | 评估批大小 |

> **安全提示**：生产环境建议通过环境变量注入 Key，而非写入配置文件。
> ```bash
> # macOS / Linux
> echo 'export AI_BAILIAN_API_KEY=your_key' >> ~/.zshrc && source ~/.zshrc
> ```

---

## 项目亮点与设计优势

### 1. 精简但完整的技术选型

项目有意控制组件数量——使用 PostgreSQL + pgvector 同时满足关系型数据与向量存储需求，避免额外引入专用向量数据库（如 Milvus）；使用 Redis Stream 替代 Kafka 实现轻量级异步消息，降低运维成本。整体架构"够用即可"，适合个人或小团队快速落地。

### 2. 可复用的异步框架抽象

`AbstractStreamProducer` / `AbstractStreamConsumer` 通过模板方法模式封装了 Redis Stream 的公共操作，新增异步任务类型（如知识库向量化）只需继承基类并实现少量抽象方法，无需重复编写消费者组注册、状态更新、重试逻辑等样板代码，具备良好的横向扩展性。

### 3. 多轮面试的真实感模拟

系统不只是简单的"出题 → 回答"，而是引入了追问机制、历史去重、阶段时长联动等细节，使模拟面试的交互节奏更贴近真实场景。统一评估引擎保证了文字和语音两种作答模式的评估一致性，输出结果可横向对比。

### 4. 可靠性保障

- **自动重试**：消费失败最多重试 3 次，超限后将状态标记为失败并记录错误摘要。
- **内容哈希去重**：相同简历内容不重复分析，节省 Token 成本。
- **评估降级兜底**：批量评估中部分批次失败时，系统仍能基于成功批次生成有效报告。

### 5. 前后端完整交付

项目提供完整的 React + TypeScript 前端，覆盖简历上传、面试进行、知识库管理、报告导出等全流程 UI，不只是一个后端服务，可直接作为完整产品演示。

---

## 未来可改进方向

### 功能层面

**多模型路由支持**
当前系统与阿里云百炼强耦合，可在 Spring AI 抽象层之上引入模型路由策略（如按任务类型选择不同 LLM），支持 OpenAI、Claude、本地 Ollama 等多供应商灵活切换，提升平台的通用性。

**简历版本管理**
目前简历仅支持上传与分析，可扩展为多版本管理，支持用户对比不同版本的分析结果，追踪简历迭代的改进效果。

**面试数据统计看板**
对用户的历史面试记录进行聚合分析，生成薄弱知识点热力图、能力雷达图、历次得分趋势等可视化报表，帮助用户有针对性地备考。

**岗位 JD 智能匹配**
允许用户粘贴目标岗位 JD，系统自动提取 JD 关键技能后与简历进行向量化匹配，输出"强匹配 / 需补强 / 简历弱点"的岗位适配度报告，并据此定制面试题集。

### 工程层面

**RAG 检索质量优化**
当前使用固定分块策略，可改进为语义感知分块（如按段落 / 标题边界切割），并引入重排序模型（Reranker）对召回片段二次排序，提升上下文注入质量。

**可观测性建设**
集成 Spring AI 的 Observability 支持，接入 Prometheus + Grafana 对 LLM 调用延迟、Token 消耗、Redis Stream 积压量等关键指标进行监控告警。

**认证与多租户**
引入 Spring Security + JWT 实现用户注册登录，支持多用户隔离的知识库与面试记录，使平台具备真正的 SaaS 能力。

**Kubernetes 部署支持**
补充 Helm Chart 或 Kustomize 配置，支持将系统一键部署至 K8s 集群，并通过 HPA 对后端 Pod 实现基于 CPU / Redis 队列积压的弹性伸缩。

---

## License

MIT License — 欢迎学习、二次开发与改进。
