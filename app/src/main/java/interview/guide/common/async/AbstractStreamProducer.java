package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                streamKey(),//redis stream的键值，每一个子类自定义
                buildMessage(payload),//存储到stream的任务，每一个子类的数据格式不一样
                AsyncTaskStreamConstants.STREAM_MAX_LEN//消息最大长度
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    //业务名称
    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    //业务唯一标识：由子类决定使用什么字段作为唯一标识
    protected abstract String payloadIdentifier(T payload);

    //发送失败处理逻辑
    protected abstract void onSendFailed(T payload, String error);
}
