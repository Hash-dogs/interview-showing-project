package interview.guide.modules.interview.model;

/**
 * 面试问题DTO
 *
 * 用于接收数据库和大模型生成的问题，通过对应函数序列化为DTO对象
 * <p>
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 *
 * @param questionIndex       问题序号，从1开始递增
 * @param question            面试具体问题内容
 * @param type                技能分类Key，用于后端逻辑处理（如 "MYSQL"、"CSS"、"DP"）
 * @param category            展示用分类标签，用于前端显示（如 "MySQL"、"CSS"、"动态规划"）
 * @param topicSummary        知识点摘要，用于历史去重压缩（如 "Redis RDB/AOF 持久化对比"）
 * @param userAnswer          用户回答内容
 * @param score               AI评分（0-100）
 * @param feedback            AI反馈建议
 * @param isFollowUp          是否为追问问题
 * @param parentQuestionIndex 父问题序号，仅当 isFollowUp 为 true 时有效
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex
) {
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null, false, null);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, answer, score, feedback, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, userAnswer, score, feedback, isFollowUp, parentQuestionIndex);
    }
}
