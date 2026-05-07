package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    //protected只能由子类构造，其他类无法构造
    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostConstruct
    public void init() {
        //独一无二（结合UUID）的consumer name，会存储再redis stream中
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        //虚拟线程执行消息队列中的内容
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),//21亿长度队列（几乎不会占满）：但是会出现内存占用过多问题，无限顶加入线程会占用内存，导致OOM
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);//设置为守护线程
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );


        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 关闭消费者线程池，停止消费循环。
     * 由 Spring 容器在销毁 Bean 时自动调用。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 启动消费者的初始化逻辑。
     * 1. 创建 Redis Stream 消费组（若不存在）。
     * 2. 进入消息消费循环。
     */
    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    /**
     * 持续从 Redis Stream 中拉取消息并处理，直到运行标志被设置为 false。
     * 捕获异常以防止单个消息处理失败导致整个消费循环终止。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    /**
     * 处理单条 Stream 消息的核心逻辑。
     * 1. 解析 payload，若解析失败则直接 ACK 丢弃。
     * 2. 执行业务处理流程：标记处理中 -> 业务逻辑 -> 标记完成 -> ACK。
     * 3. 若发生异常，根据重试次数决定重试或标记失败，并最终 ACK 消息。
     *
     * @param messageId Redis Stream 消息 ID
     * @param data      消息体数据 Map
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        // 简历分析整个过程（四个函数）
        try {
            // 修改该消息状态为 processing
            markProcessing(payload);
            // 消息前置处理，分析该消息对应的信息是否合法。前置确认安全（如简历分析，前置检查简历是否存在等）
            processBusiness(payload);
            // 修改该消息状态为 completed
            markCompleted(payload);
            // 确认信息 ACK
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            // 分析过程中出现异常也需要 ACK，避免消息重复堆积
            ackMessage(messageId);
        }
    }

    /**
     * 从消息数据中解析重试次数。
     *
     * @param data 消息体数据 Map
     * @return 重试次数，若解析失败或不存在则返回 0
     */
    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断错误信息，防止过长的错误字符串存储到数据库或日志中。
     *
     * @param error 原始错误信息
     * @return 截断后的错误信息（最大 500 字符），若为 null 则返回 null
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 向 Redis Stream 发送 ACK 确认，表示消息已处理完成（无论成功或失败）。
     *
     * @param messageId Redis Stream 消息 ID
     */
    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
