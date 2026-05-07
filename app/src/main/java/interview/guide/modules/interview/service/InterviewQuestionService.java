package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    //问题类型
    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    //最大追问数量
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final double RESUME_QUESTION_RATIO = 0.6;

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    /**默认问题：当简历问题和方向问题都生成失败的时候就使用默认问题*/
    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
        {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
        {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
        {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
        {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
        {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
        {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
    };

    //四类prompt注入器
    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;

    //将大模型输出json序列化为java对象存储
    private final BeanOutputConverter<QuestionListDTO> outputConverter;

    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    //异步虚拟线程池
    private final ExecutorService questionExecutor;
    private final int followUpCount;

    private record QuestionListDTO(List<QuestionDTO> questions) {}

    private record QuestionDTO(String question, String type, String category,
                               String topicSummary, List<String> followUps) {}

    //构造函数
    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        //异步线程池
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }

    //映射每一个prompt的path的函数
    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    //手动关闭虚拟线程池
    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }


    /**
     * 生成面试问题总逻辑
     * 无简历：全部出纯技术方向题。
     * 有简历：启动并发模式。按 6:4 比例，一个线程去生成简历相关的项目题（60%），另一个线程去生成八股文/通用方向题（40%）。如果某个线程失败，会自动降级处理。最终合并两部分题目。
     * 对于简历里的参数是由大模型识别后使用BeanOutputConverter映射到对应DTO对象的
     * @param llmProvider          语言模型提供者
     * @param skillId              技能ID
     * @param difficulty           技能难度
     * @param resumeText           简历文本
     * @param questionCount        问题数量
     * @param historicalQuestions  历史问题
     * @param customCategories     自定义分类？
     * @param jdText               职位描述
     * @return 面试问题列表
     */
    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String llmProvider,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText) {

        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        ChatClient questionChatClient =
            llmProviderRegistry.getPlainChatClient(llmProvider);

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = buildHistoricalSection(historicalQuestions);
        if (!hasResume) {
            //没有简历情况：那就只根据用户选择的技能id或岗位描述JD出题？
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection);
        }

        //有简历的情况：
        //关于简历与技能的出题数量（由constants中简历与技能出题占比来计算）
        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        //使用异步线程生成由简历所得问题
        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, historicalSection),
            questionExecutor);

        //使用异步线程生成由技能所得问题
        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount,
                historicalSection),
            questionExecutor);

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;

        //当一方生成失效时不是重新生成另一方的问题，而是查询另一方是否生成完成，如何再补充问题数量（注意历史查询）
        //这里虽然用到了异步线程，但实际获取结果操作还是串行的（等简历题生产完成才能判断技能题）
//        try {
//            resumeQuestions = resumeFuture.join();
//        } catch (CompletionException e) {
//            log.error("简历题生成失败，降级为全方向题", e.getCause());
//            directionFuture.cancel(true);
//            //全为方向题，数量改为questionCount
//            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
//                historicalSection);
//        }
//
//        try {
//            directionQuestions = directionFuture.join();
//        } catch (CompletionException e) {
//            log.error("方向题生成失败，降级为全简历题", e.getCause());
//            if (resumeQuestions.isEmpty()) {
//                return generateFallbackQuestions(skill, questionCount);
//            }
//            return resumeQuestions;
//        }
//
//        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
//            log.warn("简历题和方向题均为空，回退到默认问题");
//            return generateFallbackQuestions(skill, questionCount);
//        }



// 异步线程一起判定是否结束（只有都结束才会继续执行）

// 1. 一次性等待两个 Future 全部完成（无论成功失败）
        try {
            CompletableFuture.allOf(resumeFuture, directionFuture).join();
        } catch (CompletionException e) {
            // allOf.join() 在任一 Future 异常时也会抛出
            // 但我们不在这里处理，而是在下面分别判断每个 Future 的状态
            // 所以这里只打日志，不做任何返回
            log.debug("至少一个 Future 执行异常，进入分支判断", e.getCause());
        }

// 2. 分别判断两个 Future 的完成状态
        boolean resumeSucessed = resumeFuture.isCompletedExceptionally();
        boolean directionSucessed = directionFuture.isCompletedExceptionally();

// ==================== 四种组合分支 ====================

// 分支1：两个都失败
        if (resumeSucessed && directionSucessed) {
            log.error("简历题和方向题均生成失败，回退到默认问题");
            return generateFallbackQuestions(skill, questionCount);
        }

// 分支2：简历题失败，方向题成功
        if (resumeSucessed) {
            log.warn("简历题生成失败，方向题成功，用方向题补全至 {} 题", questionCount);
            directionQuestions = directionFuture.join(); // 已成功，join() 直接取值不会阻塞

            if (directionQuestions.size() >= questionCount) {
                // 方向题数量已经足够，直接截取
                return directionQuestions.subList(0, questionCount);
            }

            // 方向题数量不足，需要补全差额
            int supplementCount = questionCount - directionQuestions.size();
            // 把本次已生成的方向题追加到 historicalSection，防止补全时重复出题
            List<HistoricalQuestion> dirQuestions=directionQuestions.stream()
                    .filter(q -> !q.isFollowUp())//过滤掉追问问题
                    .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()))
                    .toList();
            String enrichedSection = historicalSection + buildHistoricalSection(dirQuestions);
            List<InterviewQuestionDTO> supplement = generateDirectionOnly(
                    questionChatClient, skill, difficultyDesc, supplementCount, enrichedSection
            );
            return mergeQuestionBatches(directionQuestions, supplement);
        }

// 分支3：方向题失败，简历题成功
        if (directionSucessed) {
            log.warn("方向题生成失败，简历题成功，用简历题补全至 {} 题", questionCount);
            resumeQuestions = resumeFuture.join(); // 已成功，直接取值

            if (resumeQuestions.isEmpty()) {
                // 简历题虽然"成功"但结果为空（比如简历内容太少），直接 fallback
                log.warn("简历题结果为空，回退到默认问题");
                return generateFallbackQuestions(skill, questionCount);
            }

            if (resumeQuestions.size() >= questionCount) {
                return resumeQuestions.subList(0, questionCount);
            }

            // 简历题数量不足，补全差额(同上，将已生成问题传入历史问题库中，防止补生成的问题与前面生成问题重复)
            int supplementCount = questionCount - resumeQuestions.size();
            List<HistoricalQuestion> resQuestions=resumeQuestions.stream()
                    .filter(q -> !q.isFollowUp())//过滤掉追问问题
                    .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()))
                    .toList();
            String enrichedSection = historicalSection + buildHistoricalSection(resQuestions);
            List<InterviewQuestionDTO> supplement = generateResumeQuestions(
                    questionChatClient,resumeText ,supplementCount,skill, difficultyDesc , enrichedSection
            );
            return mergeQuestionBatches(resumeQuestions, supplement);
        }

// 分支4：两个都成功，正常合并
        resumeQuestions    = resumeFuture.join();
        directionQuestions = directionFuture.join();

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题结果均为空，回退到默认问题");
            return generateFallbackQuestions(skill, questionCount);
        }


        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size(), directionQuestions.size(), merged.size());
        return merged;
    }

    /**
     * 根据简历生成面试问题
     *
     * @param questionClient       语言模型客户端
     * @param resumeText           简历文本
     * @param questionCount        问题数量
     * @param skill                技能
     * @param difficultyDesc       技能难度描述
     * @param historicalSection    历史问题
     * @return 面试问题列表
     */
    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, String historicalSection) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("resumeText", resumeText);
            variables.put("historicalSection", historicalSection);

            String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
            String userPrompt = resumeUserPromptTemplate.render(variables);

            //调用大模型生成函数invoke()
            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            questions = capToMainCount(questions, questionCount);
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 根据技能生成面试问题
     * 不根据简历内容出的题：专门调用 AI 生成无简历的纯技术/方向题。会根据预设的技能知识点比例（如 Java 集合占 20%，并发占 30%）来指导 AI 出题
     * @param questionClient       语言模型客户端
     * @param skill              技能ID
     * @param difficultyDesc           技能难度
     * @param questionCount        问题数量
     * @param historicalSection  历史问题
     * @return 面试问题列表
     */
    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc,
            int questionCount, String historicalSection) {
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));

            String systemPrompt = skillSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + GENERIC_MODE_SYSTEM_APPEND
                + outputConverter.getFormat();
            String userPrompt = skillUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            if (questions.stream().filter(q -> !q.isFollowUp()).count() == 0) {
                log.warn("方向题返回空题单，回退到默认问题");
                return generateFallbackQuestions(skill, questionCount);
            }
            questions = capToMainCount(questions, questionCount);
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败，回退到默认问题: {}", e.getMessage(), e);
            return generateFallbackQuestions(skill, questionCount);
        }
    }

    /**
     * 将“简历题”和“方向题”两个集合合并成一个完整的题单，并重新计算题目的序号（Index），确保序号连续
     * @param first 第一个题目列表（简历问题）
     * @param second 第二个题目列表（基本技能问题）
     * @return 合并后的问题List
     */
    private List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first, List<InterviewQuestionDTO> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        int offset = first.size();
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        for (InterviewQuestionDTO q : second) {
            int newIndex = q.questionIndex() + offset;
            Integer newParent = q.parentQuestionIndex() != null
                ? q.parentQuestionIndex() + offset : null;
            merged.add(InterviewQuestionDTO.create(
                newIndex, q.question(), q.type(), q.category(),
                q.topicSummary(), q.isFollowUp(), newParent));
        }
        return merged;
    }

    /**
     * 根据技能ID,从技能库中获取技能信息
     * 判断本次面试考的是系统预设的通用技能（如 Java、前端），还是根据输入 JD 临时动态生成的“自定义技能”，并返回对应的技能对象
     * @param skillId    技能ID
     * @param customCategories 自定义技能分类
     * @param jdText      职位描述
     * @return 技能信息
     */
    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    /**
     * 解析技能难度描述
     * 将前端传来的简短难度标识（如 "senior"），翻译成给 AI 看的详细文字描述（如 "3年+经验。考察架构设计和深度调优"
     * @param difficulty 技能难度
     * @return 技能难度描述
     */
    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    /**
     *  将 AI 返回的 JSON 格式数据（DTO）展平，转换为系统可用的题目列表。
     *  主要是将 AI 生成的一道主干题及其附带的“追问（Follow-ups）”拆分成一条条独立的记录，并建立父子关联。
     * @param dto AI 返回的原始数据对象
     * @return 优化后的问题列表
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            String type = (q.type() != null && !q.type().isBlank()) ? q.type().toUpperCase() : DEFAULT_QUESTION_TYPE;
            int mainQuestionIndex = index;
            questions.add(InterviewQuestionDTO.create(index++, q.question(), type, q.category(), q.topicSummary(), false, null));

            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, followUps.get(i), type,
                    buildFollowUpCategory(q.category(), i + 1), null, true, mainQuestionIndex
                ));
            }
        }

        return questions;
    }

    /**
     * 兜底防错。有时候 AI 会“不听话”生成多于要求的题目，该函数负责把多余的主问题截断剔除，确保题目数量符合预期
     * @param questions 问题列表
     * @param maxMainCount 最大主问题数量
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions, int maxMainCount) {
        long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
                //TODO 补全题目？
            }
            return questions;
        }

        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(q);
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
        return capped;
    }

    /**
     * 生成兜底问题，当 AI 输出的题目数量不足或大模型宕机等突发问题，不能让面试突然停止，会调用该函数生成兜底问题。
     * @param skill 技能信息
     * @param count 兜底问题数量
     * @return 兜底问题列表
     */
    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO cat = categories.get(generated % categories.size());
                String question = "请谈谈你在\"" + cat.label() + "\"方向的技术理解和实践经验。";
                questions.add(InterviewQuestionDTO.create(index++, question, cat.key(), cat.label(), null, false, null));
                int mainIndex = index - 1;
                for (int j = 0; j < followUpCount; j++) {
                    questions.add(InterviewQuestionDTO.create(
                        index++, buildDefaultFollowUp(question, j + 1),
                        cat.key(), buildFollowUpCategory(cat.label(), j + 1), null, true, mainIndex
                    ));
                }
                generated++;
            }
            return questions;
        }

        for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
            String[] q = GENERIC_FALLBACK_QUESTIONS[i];
            questions.add(InterviewQuestionDTO.create(index++, q[0], q[1], q[2], null, false, null));
            int mainIndex = index - 1;
            for (int j = 0; j < followUpCount; j++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, buildDefaultFollowUp(q[0], j + 1),
                    q[1], buildFollowUpCategory(q[2], j + 1), null, true, mainIndex
                ));
            }
        }
        return questions;
    }

    /**
     * 由SQl获得的历史问题列表转换为一段规范字符串的实现逻辑（用于prompt注入）
     * @param historicalQuestions 历史问题列表
     * @return 历史问题部分
     */
    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史提问";
        }

        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion hq : historicalQuestions) {
            String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
            String summary = hq.topicSummary();
            if (summary == null || summary.isBlank()) {
                String q = hq.question();
                summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
            }
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
        }

        //采用SQL存储历史提过的问题，在模拟面试问题调用者的视角下，先使用SQL查询出同一个ID下的历史问题，传入给问题生成函数
        // 不使用带有memory的chatClient原因：
        //1. 会话记忆是非结构化文本，里面内容包含所有记忆，每次提取都需要将所有记忆中有关出题的部分都提取出来。而对于SQL数据库存储只需要每次出题存储即可，还能用Hash进行快速查找，
        //2. 会话记忆越多消耗token越来越多，而SQl数据库的消耗几乎微不足道
        StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 规范化职位的描述
     * 如果有岗位描述（JD），将 JD 内容进行安全清洗（防止注入攻击）后，组装进 Prompt 中，让 AI 结合岗位需求出题
     * @param sourceJd 职位描述
     * @return 职位描述部分
     */
    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    /**
     * 构建“面试官人设”
     * 从知识库读取该技术方向的专属人设（比如要求考察底层原理，语气严肃等）拼接到系统提示词中
     * @param skill 技能信息
     * @return 技能描述部分
     */
    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
            + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
            + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

    /**
     * 清洗追问
     * 对 AI 生成的追问内容进行清洗（去除空白内容），并限制最大追问数量（系统常量限制最多 2 个追问）
     * @param followUps 追问列表
     * @return 清洗后的追问列表
     */
    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(followUpCount)
            .collect(Collectors.toList());
    }

    /**
     * 构建追问类别
     * 为追问生成友好的分类标签，比如把主问题的类别“并发编程”，转化为“并发编程（追问1）”、“并发编程（追问2）”
     * @param category 类目
     * @param order 顺序
     * @return 类目
     */
    private String buildFollowUpCategory(String category, int order) {
        String base = (category == null || category.isBlank()) ? "追问" : category;
        return base + "（追问" + order + "）";
    }

    /**
     * 构建默认追问
     * 配合兜底函数（generateFallbackQuestions）使用，当使用内置问题时，为它们生成写死格式的追问（比如：“结合真实场景展开说明”、“遇到线上异常怎么排查”）
     * @param mainQuestion 主题
     * @param order 顺序
     * @return 追问
     */
    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
        }
        return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
    }
}
