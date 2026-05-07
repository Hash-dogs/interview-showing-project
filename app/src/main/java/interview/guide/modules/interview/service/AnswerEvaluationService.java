package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.skill.InterviewSkillService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Slf4j
@Service
public class AnswerEvaluationService {

    //等价于lombok的@SLf4j注解
    //private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;

    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                   InterviewPersistenceService persistenceService,
                                   InterviewSkillService skillService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.persistenceService = persistenceService;
        this.skillService = skillService;
    }


    /**
     * 评估完整面试并生成报告
     * InterviewQuestionDTO->QaRecord->评估->EvaluationReport->InterviewReportDTO
     * @param chatClient 用于调用大模型进行智能评估的 ChatClient 实例
     * @param sessionId  当前面试会话的唯一标识，用于关联上下文及获取技能配置
     * @param resumeText 候选人的简历文本内容，作为评估的背景参考信息
     * @param questions  面试问题列表，包含问题索引、题干、分类及用户回答等详细信息
     * @return InterviewReportDTO 包含总分、维度得分、逐题点评、优缺点分析及参考答案的完整面试评估报告
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            // 转为通用问答记录
            List<QaRecord> qaRecords = questions.stream()
                .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer()))
                .toList();

            //判断skillID是否合法
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                persistenceService.findBySessionId(sessionId)//通过sessionID获取session，便于下一步获取skillID
                    .map(s -> s.getSkillId())//取出skillID
                    .orElse(null)//存在返回对应值，不存在则返回null
            );

            // 调用通用评估服务
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionId, qaRecords, resumeText, referenceContext
            );

            // 转为文字面试专用 DTO
            return toInterviewReportDTO(report);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：" + e.getMessage());
        }
    }

    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores().stream()
                .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                .toList(),
            report.questionDetails().stream()
                .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                    qe.userAnswer(), qe.score(), qe.feedback()))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                    ra.referenceAnswer(), ra.keyPoints()))
                .toList()
        );
    }
}
