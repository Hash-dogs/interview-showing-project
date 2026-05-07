package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 *
 * @param questionIndex 问题序号，从1开始
 * @param question      面试问题内容
 * @param category      问题分类/标签
 * @param userAnswer    用户回答内容，null 表示未回答
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer
) {}
