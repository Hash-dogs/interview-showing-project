package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;//是否问题重写
    private final int shortQueryLength;//短查询token判定长度
    private final int topkShort;//三类查询的topK值
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;//短查询的min score
    private final double minScoreDefault;

    //基本查询服务
    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     * 于后面优化四：使用流式输出的函数功能同级
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        //知识库计数（被查询词数）
        countService.updateQuestionCounts(knowledgeBaseIds);

        //查询重写+动态参数检索（不包含上下文历史）
        QueryContext queryContext = buildQueryContext(question, List.of());

        //向量库相似检索
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        //判断该问题是否可用,并加入短查询二次相似匹配（优化三））
        if (!hasEffectiveHit(question,relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        //构建上下文（合并知识库检索到的文档）
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        //构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        //根据知识库所得调用大模型回答
        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     * 存在方法重载（传参重载）
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * （输出时）优化四、流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2.1 判断history是否合法
            List<Message> effectiveHistory = sanitizeHistory(history);

            // 2.2 问题重写 + 动态参数检索
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);

            //2.3 向量库相似检索
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);


            //2.4 向量库检索内容是否合格
            if (!hasEffectiveHit(question,relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 将检索到的向量库chunks整合到一起，构建上下文
            String context = relevantDocs.stream()//stream流
                    .map(Document::getText)//每个 Document 对象映射成它的纯文本内容，相当于 doc -> doc.getText()
                    .collect(Collectors.joining("\n\n---\n\n"));//两个换行 + 一条 Markdown 水平线 + 两个换行

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            // var类似与auto
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);//在返回的json串中加入键值对messages：effectiveHistory
            }
            Flux<String> responseFlux = promptSpec//ChatClient类型
                    .user(userPrompt)
                    .stream() //关键点：ChatClient是可以设定为流式模式的，返回的就是Flux<String>
                    .content();//只返回流式json串中的content字段。

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);

            // 6. 流式规范化输出
            return normalizeStreamOutput(responseFlux)
                //流正常结束的时候输出日志
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                //对流中的sink::error进行兜底（不直接返回给前端），封装统一格式的报错给前端（设置友好的提示信息、保护LLM生产的信息隐私）
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     *  用户输入问题规范化（含优化一）
     *
     * @param originalQuestion 原问题
     * @param history 历史对话消息（可选）
     * @return 规范化后的问题（用于输入大模型进行评估）
     */
    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        //清洗问题（null情况以及首位出现空白字符情况）
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        //查询问题重写
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);

        //LinkedHashSet是一种有顺序的Set，这里前后add（）写明优先调用LLM优化的新问题
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        //返回搜索参数（由问题的长短决定是短，中，长查询参数。每一种对应的topk和MinScore不同,最后传到向量库的查询条件也会不同）
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        //只返回问题封装对象，但是不比较原问题和新问题的得分，即候选回退是在评估的逻辑里
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);

    }

    /**
     * （输出时）优化一（查询前优化）：查询重写
     *
     * @param question 原问题（一般是短查询才会用重写）
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));//结合上下文才能对短查询问题进行补充和扩写
            String rewritePrompt = rewritePromptTemplate.render(variables);//PromptTemplate设置在同目录下的repository中，映射对应的.st文件
            String rewritten = getChatClient().prompt()
                    .user(rewritePrompt)
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }


    /**
     * 判断History是否为空，空也要返回空List而不是null
     *
     * @param history 上下文
     * @return 合法的List（规避非法null）
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//  清洗：如果是null变换为空字符串，不是null只去除首位空白字符，中间空白不去除
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();//去除首位空白字符
    }

    /**
     * 向量相似度检索（调用KnowledgeBaseVectorService中方法）
     *
     * @param queryContext 原问题类对象
     * @param knowledgeBaseIds 知识库ID
     * @return 流式响应
     */
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        //由于LinkedHashSet的顺序特性，优先获取的是LLM优化点新问题，只要他能通过检验函数检验就不会循环到旧问题
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(candidateQuery,docs)) {
                return docs;
            }
        }
        return List.of();
    }


    /**
     * 优化二、实现动态参数检索
     * 判断问题为短，中，长查询的哪一种，并返回对应种类查询的查询条件（topK和MinScore）
     *
     * @param question 原问题
     * @return 流式响应
     */
    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }



    /** TODO
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 判断查询问题是否可用,并加入短查询关键字的二次匹配
     * 这里关键字定义模糊，目前是使用短查询全部字符作关键字。TODO 可以使用LLM判断关键字
     * @param question 原问题
     * @param docs 原问题通过向量化相似度搜索到的相关知识库
     * @return 该问题是否可用的bool
     */
    private boolean hasEffectiveHit(String question, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        //应该去除中间所有空白字符串而非之去除首尾空白字符串
        //String normalized = normalizeQuestion(question);
        String normalized = question.replaceAll("\\s+", "");
        int compactLength = normalized.length();
        //判断是否为短查询。
        // 这里写死不太好，这个字段是和短、中、长查询哪里一起的
        if (compactLength>=12) {
            return true;
        }
        //短查询：验证文档内容中是否包含查询词
        String loweredToken = normalized.toLowerCase();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text != null && text.toLowerCase().contains(loweredToken)) {
                return true;
            }
        }
        log.info("短query命中确认失败，视为无有效结果: question='{}', docs={}", normalized, docs.size());
        return false;
    }

    /**
     * 规范化助手回复
     *
     * @param answer 助手回复
     * @return 适配的助手回复
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    /**
     * 判断是否为无信息模板：根据各类无信息返回的text解析判断
     *
     * @param text 待判断文本
     * @return 是否为无信息模板
     */
    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 探测窗口对返回内容进行检测
     *  先观察前一小段流式内容（探测窗口，缓冲区），快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {

        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder(); //探测窗口

            //由于Flux流式输出是异步的，所以不能用一般的Boolean。
            AtomicBoolean passthrough = new AtomicBoolean(false); //直通判断
            AtomicBoolean completed = new AtomicBoolean(false); //结束判断

            // disposable是对某一个流式响应的控制器，能主动关停响应
            // 需要先把Disposable数组引用确定好。因为在下面subscribe的lambda表达式中不能对外部的变量重新赋值，但是可以修改内容（Java特性）
            final Disposable[] disposableRef = new Disposable[1];

            //disposable映射响应的提交
            //Spring WebFlux 帮你做了订阅和 SSE 格式封装，你不需要手动 subscribe，每当上游传输一个数据，就会带哦用一次subscribe给下游（前端调用者）
            disposableRef[0] = rawFlux.subscribe(
                    //有三个参数一一对应下游的函数
                    //onNext：输出
                    //onError：报错
                    //onComplete：首位函数，结束函数
                chunk -> {

                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }

                    if (passthrough.get()) {
                        //传输函数：传输数据给下游。subscribe本质执行的函数
                        sink.next(chunk);
                        return;
                    }

                    //判断探测窗口内容是否无信息
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        //告知下游结束
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        //告知上游停止生产：直接使用下游对上游的截停函数dispose
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    //预防实际输出内容少于探测窗口长度的场景，导致一直不输出
                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                //subscribe的错误信息传输：上游出错的时候将报错信息原样传给下游
                sink::error,
                //首尾工作
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    //预防自设定的STREAM_PROBE_CHARS较大，导致Onnext中预防段回答屯在探测窗口的逻辑没检测出来。
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            //监听下游主动取消响应的情况
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    //搜索参数
    private record SearchParams(int topK, double minScore) {
    }

    /**
     *  输入大模型的规范的问题类
     *
     * @param originalQuestion 原问题（用于非检索场所）
     * @param candidateQueries 候选集合set（含有原问题和LLM优化的新问题）
     * @param searchParams //
     */
    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}
