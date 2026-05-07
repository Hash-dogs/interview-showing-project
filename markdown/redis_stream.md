# redis stream
## 一、基本问题
1. 为什么选择redis stream
    * 相比于成熟的大型消息队列Kafka，RabbitMQ，redis支持中小型项目且本身使用redis进行缓存，不必再引用一个复杂的消息队列
    * 相比于使用postgresSQL的消息队列模式，由于postgresSQL是主数据库且担任向量数据库的职责，相对与redis来说更重要。那么对于消息队列这种有一定压力的数据库皂搓，还是交给redis以减少postgres压力。
2. 为什么使用redis的list实现阻塞队列，相比于JVM中的阻塞队列有什么优缺点
    * JVM的阻塞队列是在JVM内存中存储，JVM内存是配置文件设定的一个堆内存（512MB-4GB），一般小于本地内存RAM；redis则存储空间则是本地内存RAM，一般较大
    * redis支持持久化，哪怕宕机数据也能保存下来，JVM内则不能保证数据持久存在
    * redis可以用list结构中的LPUSH与RPOP实现队列，而使用BRPOP可以实现阻塞功能。完全能实现阻塞队列效果
    * redis可能出现消息丢失：BRPOP原理是将消息移除再处理，如果移除后消费者被挂起，那么该消息就永久丢失，也不能被其他消费者捕获来弥补执行！！
    * redis仅支持单消费者，不支持多个消费者都要取某一个消息

## 二、具体实现
* streams是redis一种新的数据类型，与list，hash同级别
* 里面数据是持久化的，不会因为取走而消失

1. 发送消息：

![img_3.png](pictures/img_3.png)
#### 接收消息：
![img_2.png](pictures/img_2.png)

![img_1.png](pictures/img_1.png)
* 同一时间内多条消息到达消息队列，而指定$是只读最新的一条，则会漏读消息

2. 消费者组解决消息漏读
* 介绍：
  ![img_6.png](pictures/img_6.png)
* 创建消费者组
  ![img_4.png](pictures/img_4.png)
* 读取消息
  ![img_5.png](pictures/img_5.png)
* 查询pending_list
  ![img_7.png](pictures/img_7.png)
  第4个参数（可选）是空闲时间：到队列之后等待的时间范围，超过则不放入pending_list中
  5、6参数是获取的消息id最小值和最大值构成的一个范围，用- +表示所有范围
  7参数是消息数量

## 三、抽象生产者、消费者（对于简历分析，RAG向量化，模拟面试等场景使用消息队列）
### 1、producer：组装消息、写入Stream、发送失败后的状态兜底
### 2、consumer：初始化消费者组、生成唯一consumerName、启动单线程循环、ACK、重试、 错误截断
### 3. 执行周期：
```text
SpringBoot启动
│
├─ 扫描Bean，实例化该类
│
├─ @PostConstruct → init()
│   ├─ 生成 consumerName（UUID）
│   ├─ 创建普通线程池（1个核心线程）
│   ├─ running.set(true)
│   └─ executorService.submit(this::startConsumer)
│       └─ 立即返回，init()结束，主线程继续启动其他Bean
│
└─ SpringBoot启动完成

【与此同时，线程池中的线程在跑】
    startConsumer()
    ├─ createStreamGroup()  建立消费组
    └─ consumeLoop()
        └─ while(running) {
               streamConsumeMessages(...)  // 阻塞轮询 Redis Stream
           }
```
* 后续只要有producer向stream中发送消息，comsumer会实时等待进行执行

## 四、分析重试问题（设置重试最大词数）
* 消费者每一次取消息进行分析，但是分析失败会放到失败队列中等待
* 对失败的信息进行确定（比如是分析简历的信息，对应的consumer逻辑就要检测简历还是否存在等信息，尽量避免再次失败）
```
 @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }

        ResumeAnalysisResponse analysis = gradingService.analyzeResume(payload.content());
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(resume, analysis);
    }
```
* 对失败信息检测后，再次放入消息队列并将词数减1（记录在新消息中），每一次事务重试都是再次存入队列，而不是直接再分配给消费者立刻重试。
* **注意！：原失败消息一定要ACK,否则会导致失败消息堆积，内存消耗**，


## 五、内存问题（无限制消息队列一定要解决的关键问题！！！）
* 由于使用无限制队列进行消息队列线程存储，所以容易出现OOM情况
* 设定MAXLEN限定最大消息数，防止OOM
* 使用stream自带的模糊剪枝进行限定
```angular2html
StreamAddArgs.entries(message).trimNonStrict().maxLen(maxLen)
//去掉trimNonStrict()就是精确剪枝，会先计算需要裁剪精确数量，从旧数据开始裁剪（即编号小的数据），然后减去1-对应编号的数据
//模糊剪枝是按照块进行剪枝（redis底层就是使用块进行存储），计算删除数量，从旧数据开始裁剪，但是是裁剪整一个快，不会拆开某一个块进行裁剪。
```

## 六、优化点1（TODO）：对于某一个消费者宕机情况下将消息堆放在pending队列中造成内存过大
1. 解决方法：设定一个stream monitor类（专门实现stream中某些功能），定期扫描pending表中长时间未ACK的消息，通过器stream key识别业务类型，交给对应业务stream并作前置检查（process business函数）
2. 注意要加入分布式锁，由于是一个生产者多个消费者会导致线程问题，锁确保一个时间只有一个消费者扫描pending序列和或取信息。


