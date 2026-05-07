package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final ResourceLoader resourceLoader;

    // 批次评估结果类
    /**
     * 批次评估报告
     * @param overallScore 该批次的综合评分（0-100）
     * @param overallFeedback 该批次的综合评语
     * @param strengths 候选人在该批次表现出的优势点列表
     * @param improvements 候选人在该批次需要改进的方面列表
     * @param questionEvaluations 该批次中每道题的详细评估结果
     */
    private record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    /**
     * 单题评估详情
     * @param questionIndex 题目在完整问答列表中的索引（从0开始）
     * @param score 该题得分（0-100）
     * @param feedback 针对该题回答的具体反馈与建议
     * @param referenceAnswer 该题的标准参考答案或关键点摘要
     * @param keyPoints 回答中涵盖的关键知识点或要点列表
     */
    private record QuestionEvalDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    /**
     * 批次处理结果包装
     * @param startIndex 当前批次在原始问答列表中的起始索引（包含）
     * @param endIndex 当前批次在原始问答列表中的结束索引（不包含）
     * @param report 当前批次的评估报告，若评估失败可能为 null
     */
    private record BatchResult(
        int startIndex,
        int endIndex,
        BatchReportDTO report
    ) {}

    /**
     * 二次汇总结果
     * @param overallFeedback 全局综合评语，整合各批次反馈
     * @param strengths 全局优势点列表，去重后保留核心亮点
     * @param improvements 全局改进建议列表，去重后保留核心不足
     */
    private record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    /**
     * 评估面试问答（文字和语音通用）
     *
     * @param chatClient  LLM 客户端
     * @param sessionId   会话ID（用于日志）
     * @param qaRecords   问答记录列表
     * @param resumeText  简历摘要（可选，可为 null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    /**
     * 评估面试问答（文字和语音通用，支持参考基线）
     *
     * @param chatClient      LLM 客户端
     * @param sessionId       会话ID（用于日志）
     * @param qaRecords       问答记录列表
     * @param resumeText      简历摘要（可选，可为 null）
     * @param referenceContext SkillId基线内容（不存在为null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        // 超长简历截断，保留前 3000 字符（约 1500~2000 tokens），避免极端情况下 token 消耗过大
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }

        //传入skillId,不存在为null
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }

        // 分批评估
        List<BatchResult> batchResults = evaluateInBatches(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        //合并批次结果（防止上下文token超限）
        //1、将分批评估结果拆分为单体评估结果
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);

        //2、合并没一个批次的综合评价
        String fallbackFeedback = mergeOverallFeedback(batchResults);

        //3、合并各批次的优势点
        List<String> fallbackStrengths = mergeListItems(batchResults, true);

        //4、合并个批次的改进建议
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // 二次汇总
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        //构建报告返回EvaluationReport给评估调用者
        return buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 分批执行面试评估
     * 将问答记录按配置的批次大小拆分，逐批调用 LLM 进行评估，以控制单次 Prompt 的 Token 消耗。
     *
     * @param chatClient      LLM 客户端
     * @param sessionId       会话ID（用于日志追踪）
     * @param resumeContext   简历上下文信息
     * @param qaRecords       完整的问答记录列表
     * @param referenceContext 参考基线内容（如 SkillId 对应的标准答案或知识点）
     * @return 各批次的评估结果列表，包含起始索引、结束索引及该批次的报告
     */
    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    /**
     * 评估单个批次的问答记录
     * 构建 Prompt 并调用 LLM 进行结构化输出解析。若调用失败，返回 null 由上层合并逻辑进行兜底处理。
     *
     * @param chatClient      LLM 客户端
     * @param sessionId       会话ID（用于日志追踪）
     * @param resumeContext   简历上下文信息
     * @param referenceContext 参考基线内容
     * @param batch           当前批次的问答记录子集
     * @return 批次评估报告 DTO，若发生异常则返回 null
     */
    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size(), e.getMessage(), e);
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    /**
     * 构建批次问答记录的文本格式
     * 将 QaRecord 列表转换为 LLM 可理解的 Prompt 片段，包含题号、分类、问题内容及用户回答。
     * 若用户未回答，则标记为“(未回答)”。
     *
     * @param batch 当前批次的问答记录子集
     * @return 格式化后的问答文本字符串
     */
    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
         sb.append(String.format("问题%d [%s]: %s\n",
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }   

    /**
     * 合并分批评估的单题结果
     * 将各批次的 QuestionEvalDTO 按原始索引顺序拼接。
     * 若某批次评估失败或题目缺失，则生成默认的低分占位对象，确保最终列表长度与原始问答记录数一致。
     *
     * @param batchResults 分批评估的结果列表
     * @return 按原始顺序排列的单题评估详情列表
     */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        // 遍历每一个分批
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            // 遍历分批内每一个问题评估结果
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    // 包含单题处理失败的情况，生成默认占位对象
                    merged.add(new QuestionEvalDTO(
                        result.startIndex() + i, 0,
                        "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()
                    ));
                }
            }
        }
        return merged;
    }


    /**
     * 合并各批次的综合评语
     * 将所有非空且有效的批次评语通过双换行符拼接。若所有批次均未生成有效评语，则返回默认提示语。
     *
     * @param batchResults 分批评估的结果列表
     * @return 合并后的综合评语字符串
     */
    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(BatchReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    /**
     * 合并各批次的优势点或改进建议
     * 根据 strengthsMode 标志位选择提取优势点（strengths）或改进建议（improvements）。
     * 对提取的条目进行去重、修剪空白处理，并限制最终返回数量不超过 8 条。
     *
     * @param batchResults  分批评估的结果列表
     * @param strengthsMode true 表示合并优势点，false 表示合并改进建议
     * @return 合并并去重后的字符串列表，最多包含 8 个条目
     */
    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                    //String的trim方法：String的trim方法作用是什么11:40Claude responded: 去除字符串首尾的空白字符（空格、制表符 \t、换行 \n 等），中间的空白不受影响。去除字符串首尾的空白字符（空格、制表符 \t、换行 \n 等），中间的空白不受影响
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }



    /**
     * 二次汇总各批次评估结果
     * 基于分批评估得到的单题详情、核心优势点、改进建议以及分类得分、综合评语，调用 LLM 进行全局综合汇总。
     * 生成最终的综合评语、核心优势点及改进建议。若 LLM 调用失败或返回无效数据，则降级使用批次聚合的兜底结果。
     *
     * @param chatClient           LLM 客户端
     * @param sessionId            会话ID（用于日志追踪）
     * @param resumeContext        简历上下文信息
     * @param referenceContext     参考基线内容（如 SkillId 对应的标准答案或知识点）
     * @param qaRecords            完整的问答记录列表
     * @param evaluations          合并后的单题评估详情列表
     * @param fallbackFeedback     批次聚合产生的兜底综合评语
     * @param fallbackStrengths    批次聚合产生的兜底优势点列表
     * @param fallbackImprovements 批次聚合产生的兜底改进建议列表
     * @return 二次汇总后的结果 DTO，包含全局评语、优势点及改进建议
     */
    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback =
                    dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                    ? dto.overallFeedback()
                    : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    /**
     * 清洗并规范化列表（优势点或改进建议）
     * 优先使用主列表，若主列表为空或 null 则回退到备用列表。
     * 对条目进行非空校验、去除首尾空白、去重处理，并限制最多返回 8 条。
     *
     * @param primary  主列表（通常来自 LLM 二次汇总结果）
     * @param fallback 备用列表（通常来自批次聚合的兜底结果）
     * @return 清洗后的字符串列表，最多包含 8 个不重复的非空条目
     */
    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    /**
     * 构建最终评估报告
     * 将分批评估得到的单题详情、核心优势点、改进建议以及分类得分、综合评语等信息组装成标准的 EvaluationReport 对象。
     * 同时计算整体平均分，并处理未回答或评估失败题目的默认值兜底。
     *
     * @param sessionId       会话ID
     * @param qaRecords       原始问答记录列表
     * @param evaluations     合并后的单题评估详情列表
     * @param overallFeedback 全局综合评语
     * @param strengths       全局优势点列表
     * @param improvements    全局改进建议列表
     * @return 完整的面试评估报告
     */
    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback() : "该题未成功生成评估反馈。";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        int overallScore = answeredCount == 0 ? 0
            : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        return new EvaluationReport(
            sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }

    /**
     * 构建分类得分摘要文本
     * 统计每个问题分类下的得分情况，计算平均分和题目数量，格式化为文本字符串。
     * 用于二次汇总 Prompt 中提供结构化的分类表现概览。
     *
     * @param qaRecords   原始问答记录列表
     * @param evaluations 合并后的单题评估详情列表
     * @return 格式化的分类得分摘要字符串，每行一个分类
     */
    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    /**
     * 构建单题高光时刻摘要文本
     * 提取每道题的简要信息（题号、问题摘要、得分、反馈摘要），用于二次汇总 Prompt 中
     * 让 LLM 快速了解每道题的具体表现。限制最多返回前 20 条以控制 Token 消耗。
     *
     * @param qaRecords   原始问答记录列表
     * @param evaluations 合并后的单题评估详情列表
     * @return 格式化的单题高光摘要字符串，每行一道题
     */
    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}
