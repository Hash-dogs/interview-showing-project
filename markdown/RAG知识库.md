# RAG知识库：postgresSQL数据库+pgvector想来数据库插件


## 一、知识库上传
1. 分块策略一（原文档使用）
    * 使用spring ai配置类的分块方法：默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
    * 对于每一个分块都有加上元数据（知识库ID、存储路径、大小、hash值等）封装，用于保存到SQl数据库
```
    this.textSplitter = TokenTextSplitter.builder()
    .withChunkSize(500)          // 每块目标 token 数，默认800
    .withMinChunkSizeChars(350)  // 块的最小字符数
    .withMinChunkLengthToEmbed(5)// 太短的块直接丢弃
    .withMaxNumChunks(10000)     // 最多切多少块
    .withKeepSeparator(true)     // 是否保留分隔符
    .build();
```

2. 分块策略二（优化使用）：结构感知+父子分块
    * 结构感知：由于简历是强结构的文档，结构感知能避免同一模块内容不被拆分
    * 父子分块：子块是父块的某一部分，向量模型检索的是子块，但是返回给调用者的是整一个父块（避免同一模块的上下文被拆分；同时子块粒度小、检索精度较高）


3. 向量化及存入向量数据库
   * SQL以及rustfs的步骤类似简历上传
   * 由于向量化以及上传向量数据库时间较长，不能做阻塞等待。所以使用异步：redis stream
   * 向量模型的API在yml中配置，分块策略则在具体向量化类中配置
   * 向量化与向量数据库是分开的，只是spring ai这类框架将其整合在vectorstore的add方法中了
 
## 二、知识库检索
采用HNSW算法

## 知识库相似度查询以及优化
### 1.向量相似度搜索（支持多知识库联查）
* 多知识库联查本质是利用传入的知识库ID，通过转换为postgresSQL的where语句进行chunks的搜索
* 通过vectorstore的SearchRequest()进行检索和相似度分析
* 构建好SearchRequest就可以传入到vectorstore内的同名函数SimilaritySearch(),所以在service在设定一个similaritySearch函数只是为了给controller传入参数的时候更简洁明了，而不是传入封装的SearchRequest类对象。

```
SearchRequest request = SearchRequest.builder()
    .query(query)                          // 需要向量化的文本（string）
    .topK(Math.max(topK, 1))              // 相似度前k个
    .similarityThreshold(minScore)         // 最低接受的相似度
    .filterExpression(...)                 // 过滤元数据条件
    .build(); 
```

### 2. (搜索前)优化一:问题重写(LLM优化查询语句) + 候选回退
定义：
* 问题重写：用户的原始输入往往口语化、信息不完整，直接拿去向量搜索效果差。将history加入到prompt中，让LLM生成新的问题问题
* 候选回退：对于重写内容如果相似度搜索得分较低，再使用原文本进行搜索
举例：
```angular2html
用户输入："退款咋整"
        ↓ LLM 重写
优化后："申请退款的具体流程和所需材料是什么"
        ↓ 向量搜索效果更好
```
### 3. (搜索时)优化二：动态参数 
定义：根据查询长度自适应调整传参 [topK/minScore]
实现原理：设定三种查询等级：短，中，长。由问题长度判定等级。每一个等级有对应的topK和MinScore
举例：
```angular2html
短查询："退款"
→ 语义模糊，topK 调大（多捞一些）
→ minScore 调低（放宽标准）
→ 宁可多要几条，再让LLM筛选

长查询："我在平台上购买了一件商品，已经超过7天了，请问还能申请退款吗"
→ 语义明确，topK 调小（精准匹配）
→ minScore 调高（严格标准）
→ 直接要最相关的几条就够了
```
### 4. (搜索后)优化三：短 token 二次内容匹配
定义：短查询向量化后语义信息太少，向量搜索结果不可靠，所以加一轮关键词精确匹配做二次确认
实现细节：直接使用循环遍历相似度搜索到的文档，使用List基本函数进行判定文档中是否含有关键字
举例：
```angular2html
query = "退款"（很短）
        ↓ 第一轮：向量搜索
返回5个候选 chunk
        ↓ 第二轮：检查这5个chunk里
          是否真的包含"退款"这个关键词
        ↓
包含关键词的 chunk 优先级提升 ✅
不包含关键词的 chunk 降级或丢弃 ❌
```
### 5. (输出时)优化四：流式输出 SSE推送
* 定义：SSE（Server-Sent Events） 是服务器向客户端实时推送数据的技术，让用户看到 LLM 逐字输出的效果
```
服务端                          客户端
  │  data: "根据"               │
  │─────────────────────────→  │  显示："根据"
  │  data: "您的"               │
  │─────────────────────────→  │  显示："根据您的"
  │  data: "订单信息"            │
  │─────────────────────────→  │  显示："根据您的订单信息"
```
* 实现细节：ChatClient中封装有流式输出的逻辑，返回类型是都是Flux响应类型，其中content()返回的是json串中的content字段，类型是Flux<String>.
```
            Flux<String> responseFlux = promptSpec  //ChatClient类型
                    .user(userPrompt)//加入用户提示词
                    .stream() //关键点：ChatClient是可以设定为流式模式的，返回的就是Flux<String>
                    .content();//只返回流式json串中的content字段。
```
#### 补充：
1. chatclient的stream接口是可以根据接收的数据类型返回不一样的结果
```
	interface StreamResponseSpec {
		Flux<ChatClientResponse> chatClientResponse();
		Flux<ChatResponse> chatResponse();
		Flux<String> content();
	}
```
2. 其中ChatResponse是SpringAI封装的返回结构体，能抹平各种provider的返回差异，结构如下
```
ChatResponse
├── List<Generation>          // 模型生成的结果（可能有多个候选）
    │   ├── AssistantMessage      // content文本内容就在这里
    │   └── ChatGenerationMetadata
    │       └── finish_reason     // 停止原因（STOP/LENGTH/CONTENT_FILTER等）
    └── ChatResponseMetadata
    ├── model                 // 使用的模型名
    ├── id                    // 响应ID
    └── Usage
    ├── promptTokens      // 输入消耗token数
    └── generationTokens  // 输出消耗token数
```
3. ChatClientResponse则是封装了ChatResponse的包含上下文等额外信息的结构体
```
ChatClientResponse
├── ChatResponse              // 上面那个完整对象
└── ChatClientContext         // ChatClient 自己的上下文信息
    ├── advisors 的处理结果   // 比如 RAG advisor、日志 advisor 等中间件的附加数据
    └── 其他 ChatClient 级别的元数据
```
### 6.（输出时）优化五：探测窗口归一化
定义：探测窗口归一化： LLM 流式输出时，token 是一部分一部分吐出来的，
原因：
* utf-8编码中按照字节继续编码，所以可能会导致一个词语根据字节划分在两个块中
* LLM 的输出分词器在切分文本时，是基于统计概率的，对于多个单字组成的词会被划分
* 对于一些特定场景，需要将一些跨越很大篇幅的语法标记一同传输（如markdown的\*\*加粗\*\* 或 ```）
解决办法，使用缓存窗口继续人为逻辑判断避免

```
错误情况（直接推送）：
"退" → "款" → "流" → "程"   ← 每次只有一部分词，前端显示抖动

探测窗口归一化：
设定一个小缓冲窗口，攒够一个完整语义单元再推送
"退款" → "流程" → "是"      ← 前端显示更流畅自然
```


## RISEN规范的prompt如何书写
* Role：指定角色，能有效激活模型权重中与相关角色匹配的知识库
* Instruction：指定核心目标，引导模型集中算力解决核心问题
* standards：设定量化标准规范执行，规范输出的格式（确保与对接项目设计的字段一致）
* examples：提供参考案例（复杂案例可添加，十分奏效）
* negations：明确禁止事项，语气要坚决。

## RAG 召回率低怎么排查？
### 【1】数据质量检查 (Data Quality)
排查点：文档是否存在乱码、格式解析错误（如 PDF 表格错位）、无意义的停用词过多。
对策：优化 ETL（清洗、脱敏、去重）。
### 【2】Query 侧的语义鸿沟 (Query Transformation)
排查点：用户提问太简短、太模糊，或者包含了 Embedding 模型不理解的术语。
对策：
Query Rewrite (改写)：利用 LLM 将用户问题改写得更标准。
Query Expansion (扩展)：生成多个同义问题或生成假设性回复（HyDE）。
### 【3】向量表征能力 (Embedding Model)
排查点：通用模型对垂直领域（医疗、法律、代码）的理解不足。
对策：切换模型（如 BGE, m3e）或进行领域微调（Fine-tuning）。
### 【4】检索策略单一
排查点：纯向量检索对“关键词/专有名词”不敏感。
对策：引入混合检索 (Hybrid Search) = 向量检索 (Semantic) + 关键词检索 (BM25)。
### 【5】召回数量 (Top-K) 过小
排查点：设置的k值太小（比如只取前 3 条），导致相关信息被挤在后面。
对策：增大初始召回量（如召回 50-100 条），交给后续的 Rerank 处理。


## RAG检索，增强，生成三个环节如何配合实现检索增强
1. 检索：将输入向量化，使用向量索引再数据库中寻找向量相似度最高的块
2. 增强：将寻找的块进行清洗排序，突出最相关的知识块，如何与特定的prompt结合
3. 生成：通过特定的prompt规范大模型进行最终的推理和表达，能有效对抗幻觉，同时规范化输出。

## prompt防注入手段
1. 对一些获取系统prompt的指令进行排查
2. 处理一些特意引导大模型输出幻觉的指令进行排查，如引导输出知识库中不存在的内容

## 知识库量级上去后如何解决性能下降问题
1. 父子分块索引
2. 多个向量数据库，用的多的一个库，用的少的一个库
3. 元数据过滤，在向量检索前用结构化条件缩小候选集，用LLM或人工逻辑判断该文档的种类，创建时间、重要性等参数用于检索
4. 设立文档存入门槛，存入前对文档进行打分（相似度，重要性，关键词密度、时效性），低分文档不进入主索引，相似度极高文档提示用户进行合并操作