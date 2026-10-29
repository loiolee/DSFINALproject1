package com.example.surveysystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二阶段验收测试：严格对应课程「六、测试要求」列出的六类必测项。
 *
 * <p>六个测试方法按课程要求一一命名，顺序即课程列出的顺序：</p>
 * <ol>
 *   <li>{@link #用例1_创建问卷测试()} —— 创建问卷</li>
 *   <li>{@link #用例2_添加题目测试()} —— 添加题目</li>
 *   <li>{@link #用例3_跳转逻辑测试()} —— 跳转逻辑</li>
 *   <li>{@link #用例4_校验测试()} —— 校验</li>
 *   <li>{@link #用例5_提交问卷测试()} —— 提交问卷</li>
 *   <li>{@link #用例6_统计测试()} —— 统计</li>
 * </ol>
 *
 * <p>与第一阶段 {@code SurveyFlowIntegrationTest} 的分工：那个类按功能模块组织，验证第一阶段需求；
 * 本类按课程测试要求组织，除第一阶段能力外<b>额外覆盖第二阶段八条新需求</b>
 * （题库引用、版本化、换绑、共享授权、跨问卷统计），使「六类必测项」的每一类都能体现新功能。</p>
 *
 * <p>测试方式：真实启动 Web 容器（随机端口）+ 真实 MongoDB，用 {@code TestRestTemplate} 发真实 HTTP 请求，
 * 完整经过鉴权拦截器、参数校验、业务校验、异常处理、持久化。断言同时检查状态码、提示文本与响应字段。</p>
 *
 * <p>每个用例自建独立账号与问卷，用例之间不共享数据，可单独执行；结束后按用户名前缀 {@code acc_}
 * 清理全部测试数据（含第二阶段新增的三个集合）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequiredAcceptanceTest {

    private static final String PASSWORD = "password123";
    /** 测试账号前缀，用于结束后精确清理，不影响项目原有数据与第一阶段测试数据（itest_）。 */
    private static final String USERNAME_PREFIX = "acc_";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsernames = new ArrayList<>();

    /* ==================================================================== 基础设施 */

    /** 统一响应视图：状态码 + 响应 JSON，便于断言 message 与 data。 */
    private record Api(int status, JsonNode json) {
        String message() {
            return json.path("message").asText("");
        }

        JsonNode data() {
            return json.path("data");
        }
    }

    private Api call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        JsonNode json;
        try {
            String text = response.getBody();
            json = (text == null || text.isBlank()) ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), ex);
        }
        return new Api(response.getStatusCode().value(), json);
    }

    /** 注册并登录一个新账号，返回登录令牌。 */
    private String newUserToken() {
        String username = USERNAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        createdUsernames.add(username);
        Api register = call(HttpMethod.POST, "/api/auth/register",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(200, register.status(), "前置条件：注册应成功，实际 " + register.status() + " " + register.message());
        Api login = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(200, login.status(), "前置条件：登录应成功，实际 " + login.status() + " " + login.message());
        String token = login.data().path("token").asText();
        assertFalse(token.isBlank(), "前置条件：登录应返回令牌");
        return token;
    }

    /** 创建问卷，断言成功并返回问卷 ID。 */
    private String createSurvey(String token, Map<String, Object> payload) {
        Api api = call(HttpMethod.POST, "/api/surveys", payload, token);
        assertEquals(200, api.status(), "前置条件：创建问卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("id").asText();
    }

    /** 发布问卷，断言成功并返回公开链接 ID。 */
    private String publish(String token, String surveyId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + surveyId + "/publish", null, token);
        assertEquals(200, api.status(), "前置条件：发布问卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("publicId").asText();
    }

    /** 开始答卷，断言成功并返回答卷 ID。 */
    private String startResponse(String token, String publicId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token);
        assertEquals(200, api.status(), "前置条件：开始答卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("responseId").asText();
    }

    /** 提交一道题的答案。 */
    private Api answer(String token, String responseId, String questionId, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questionId", questionId);
        body.put("answer", value);
        return call(HttpMethod.POST, "/api/responses/" + responseId + "/answer", body, token);
    }

    /** 提交答卷。 */
    private Api submit(String token, String responseId) {
        return call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token);
    }

    /** 判断问卷列表中是否包含指定问卷。 */
    private boolean containsSurvey(JsonNode array, String surveyId) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode node : array) {
            if (surveyId.equals(node.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ 请求体构造器 */

    private Map<String, Object> option(String id, String label, int order) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", label);
        item.put("order", order);
        return item;
    }

    private Map<String, Object> constraints(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private Map<String, Object> jump(String op, String optionId, String value, String targetQuestionId) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("op", op);
        if (optionId != null) {
            rule.put("optionId", optionId);
        }
        if (value != null) {
            rule.put("value", value);
        }
        rule.put("targetQuestionId", targetQuestionId);
        return rule;
    }

    private Map<String, Object> question(String id, int order, String type, String title, boolean required,
                                         List<? extends Map<String, ?>> options,
                                         Map<String, ?> constraints,
                                         List<? extends Map<String, ?>> jumps) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("order", order);
        item.put("type", type);
        item.put("title", title);
        item.put("required", required);
        List<Object> optionList = new ArrayList<>();
        options.forEach(o -> optionList.add(new LinkedHashMap<String, Object>(o)));
        item.put("options", optionList);
        if (constraints != null) {
            item.put("constraints", new LinkedHashMap<String, Object>(constraints));
        }
        List<Object> jumpList = new ArrayList<>();
        jumps.forEach(j -> jumpList.add(new LinkedHashMap<String, Object>(j)));
        item.put("jumps", jumpList);
        return item;
    }

    /** 只含一道单选题的最小问卷，用于验证「创建问卷」本身。 */
    private Map<String, Object> singleQuestionSurvey(String title) {
        Map<String, Object> survey = new LinkedHashMap<>();
        survey.put("title", title);
        survey.put("description", "创建问卷测试");
        survey.put("questions", List.of(question("q1", 1, "single", "请选择一个选项", true,
                List.of(option("o1", "选项一", 1), option("o2", "选项二", 2)), null, List.of())));
        return survey;
    }

    /** 四种题型的标准问卷：单选 + 多选（1~2）+ 数字（0~5000 整数）+ 文本（2~500 字）。无跳转规则。 */
    private Map<String, Object> fourTypeSurvey(String title) {
        Map<String, Object> survey = new LinkedHashMap<>();
        survey.put("title", title);
        survey.put("description", "四题型测试问卷");
        survey.put("anonymous", false);
        survey.put("allowMultiple", false);
        List<Object> questions = new ArrayList<>();
        questions.add(question("q1", 1, "single", "你喜欢户外活动吗？", true,
                List.of(option("q1_yes", "喜欢", 1), option("q1_no", "不喜欢", 2)), null, List.of()));
        questions.add(question("q2", 2, "multiple", "你喜欢哪些活动？", true,
                List.of(option("q2_walk", "徒步", 1), option("q2_cycle", "骑行", 2), option("q2_camp", "露营", 3)),
                constraints("minSelect", 1, "maxSelect", 2), List.of()));
        questions.add(question("q3", 3, "number", "每月愿意花多少元？", false,
                List.of(), constraints("minValue", 0, "maxValue", 5000, "isInteger", true), List.of()));
        questions.add(question("q4", 4, "text", "其他建议", false,
                List.of(), constraints("minLength", 2, "maxLength", 500), List.of()));
        survey.put("questions", questions);
        return survey;
    }

    /** 往问卷请求体里追加一道题，返回新的请求体（不修改入参）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withExtraQuestion(Map<String, Object> survey, Map<String, Object> extra) {
        Map<String, Object> copy = new LinkedHashMap<>(survey);
        List<Object> questions = new ArrayList<>((List<Object>) survey.get("questions"));
        questions.add(extra);
        copy.put("questions", questions);
        return copy;
    }

    /** 复制一份问卷请求体并打开「允许重复填写」，供同一用户在同一问卷上多次走流程的用例使用。 */
    private Map<String, Object> withAllowMultiple(Map<String, Object> survey) {
        Map<String, Object> copy = new LinkedHashMap<>(survey);
        copy.put("allowMultiple", true);
        return copy;
    }

    /** 题库题目创建请求体（QuestionContentRequest）。 */
    private Map<String, Object> libraryQuestionPayload(String title, String type,
                                                       List<? extends Map<String, ?>> options,
                                                       Map<String, ?> constraints) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("type", type);
        body.put("required", true);
        List<Object> optionList = new ArrayList<>();
        options.forEach(o -> optionList.add(new LinkedHashMap<String, Object>(o)));
        body.put("options", optionList);
        if (constraints != null) {
            body.put("constraints", new LinkedHashMap<String, Object>(constraints));
        }
        body.put("changeNote", "验收测试创建");
        return body;
    }

    /** 创建一道题库题目，返回 [questionId, versionId]。 */
    private String[] createLibraryQuestion(String token, String title, String type,
                                           List<? extends Map<String, ?>> options,
                                           Map<String, ?> constraints) {
        Api api = call(HttpMethod.POST, "/api/questions",
                libraryQuestionPayload(title, type, options, constraints), token);
        assertEquals(200, api.status(), "前置条件：创建题库题目应成功，实际 " + api.status() + " " + api.message());
        return new String[]{api.data().path("id").asText(), api.data().path("currentVersion").path("id").asText()};
    }

    /** 问卷内引用题库版本的题目输入项：只传引用字段，不传内容字段。 */
    private Map<String, Object> libraryRef(String instanceId, int order, String questionId,
                                           String versionId, int versionNo) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", instanceId);
        item.put("order", order);
        item.put("questionId", questionId);
        item.put("questionVersionId", versionId);
        item.put("versionNo", versionNo);
        item.put("jumps", List.of());
        return item;
    }

    /* ================================================================ 用例 1：创建问卷 */

    /**
     * 课程必测项「创建问卷测试」。
     *
     * <p>验证：未登录被拒、正常创建返回草稿、创建者可见、他人不可见、四个字段级非法输入被拒、
     * 题目实例 ID 重复被拒、同一用户可创建多份且互不影响。</p>
     */
    @Test
    @Order(1)
    void 用例1_创建问卷测试() {
        String token = newUserToken();
        String otherToken = newUserToken();

        // 步骤1：未登录创建
        Api noAuth = call(HttpMethod.POST, "/api/surveys", singleQuestionSurvey("未登录创建"), null);
        assertEquals(401, noAuth.status(), "步骤1 未登录创建问卷应返回 401，实际 " + noAuth.status());

        // 步骤2：正常创建
        Api created = call(HttpMethod.POST, "/api/surveys", singleQuestionSurvey("创建问卷测试"), token);
        assertEquals(200, created.status(), "步骤2 创建问卷应返回 200，实际 " + created.status() + " " + created.message());
        String surveyId = created.data().path("id").asText();
        assertFalse(surveyId.isBlank(), "步骤3 创建成功应返回问卷 ID");

        // 步骤4~6：返回值检查
        assertEquals("draft", created.data().path("status").asText(), "步骤4 新建问卷状态应为 draft");
        assertEquals("创建问卷测试", created.data().path("title").asText(), "步骤5 返回标题应与请求一致");
        assertEquals(1, created.data().path("questions").size(), "步骤6 请求中的 1 道题应被保存");

        // 步骤7~8：创建者可读
        Api detail = call(HttpMethod.GET, "/api/surveys/" + surveyId, null, token);
        assertEquals(200, detail.status(), "步骤7 创建者查看详情应返回 200，实际 " + detail.status());
        assertEquals("创建问卷测试", detail.data().path("title").asText(), "步骤8 详情标题应与创建时一致");

        // 步骤9~10：出现在自己的列表
        Api mine = call(HttpMethod.GET, "/api/surveys/mine", null, token);
        assertEquals(200, mine.status(), "步骤9 我的问卷列表应返回 200");
        assertTrue(containsSurvey(mine.data(), surveyId), "步骤10 新建问卷应出现在创建者的列表中");

        // 步骤11~12：他人不可见
        assertEquals(403, call(HttpMethod.GET, "/api/surveys/" + surveyId, null, otherToken).status(),
                "步骤11 其他用户查看他人问卷详情应返回 403");
        assertFalse(containsSurvey(call(HttpMethod.GET, "/api/surveys/mine", null, otherToken).data(), surveyId),
                "步骤12 其他用户的问卷列表不应包含该问卷");

        // 步骤13~14：标题为空
        Api blankTitle = call(HttpMethod.POST, "/api/surveys", singleQuestionSurvey(""), token);
        assertEquals(400, blankTitle.status(), "步骤13 标题为空应返回 400，实际 " + blankTitle.status());
        assertTrue(blankTitle.message().contains("问卷标题不能为空"),
                "步骤14 应提示标题不能为空，实际: " + blankTitle.message());

        // 步骤15~16：标题超长
        Api longTitle = call(HttpMethod.POST, "/api/surveys", singleQuestionSurvey("标".repeat(201)), token);
        assertEquals(400, longTitle.status(), "步骤15 标题超过 200 字应返回 400，实际 " + longTitle.status());
        assertTrue(longTitle.message().contains("200"),
                "步骤16 应提示长度上限 200，实际: " + longTitle.message());

        // 步骤17~18：题目列表为空
        Map<String, Object> noQuestion = new LinkedHashMap<>();
        noQuestion.put("title", "没有题目的问卷");
        Api emptyQuestions = call(HttpMethod.POST, "/api/surveys", noQuestion, token);
        assertEquals(400, emptyQuestions.status(), "步骤17 没有任何题目应返回 400，实际 " + emptyQuestions.status());
        assertTrue(emptyQuestions.message().contains("至少需要一道题目"),
                "步骤18 应提示至少需要一道题目，实际: " + emptyQuestions.message());

        // 步骤19~20：题目实例 ID 重复
        Map<String, Object> duplicateId = new LinkedHashMap<>();
        duplicateId.put("title", "题目 ID 重复的问卷");
        duplicateId.put("questions", List.of(
                question("same", 1, "single", "第一题", true,
                        List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of()),
                question("same", 2, "text", "第二题", false, List.of(), null, List.of())));
        Api duplicated = call(HttpMethod.POST, "/api/surveys", duplicateId, token);
        assertEquals(400, duplicated.status(), "步骤19 题目 ID 重复应返回 400，实际 " + duplicated.status());
        assertTrue(duplicated.message().contains("题目 ID 不能重复"),
                "步骤20 应提示题目 ID 不能重复，实际: " + duplicated.message());

        // 步骤21：同一用户创建多份互不影响
        String secondSurveyId = createSurvey(token, singleQuestionSurvey("第二份问卷"));
        Api list = call(HttpMethod.GET, "/api/surveys/mine", null, token);
        assertTrue(containsSurvey(list.data(), surveyId) && containsSurvey(list.data(), secondSurveyId),
                "步骤21 同一用户创建多份问卷，列表应同时包含两份");
    }

    /* ================================================================ 用例 2：添加题目 */

    /**
     * 课程必测项「添加题目测试」。
     *
     * <p>验证：草稿逐步加题、四种题型的选项与约束正确落库、顺序正确、
     * 五类结构非法输入被拒；并覆盖第二阶段「从题库引用题目」——引用时只传版本 ID，
     * 内容由版本快照复制，篡改内容会被拒绝（引用即只读）。</p>
     */
    @Test
    @Order(2)
    void 用例2_添加题目测试() {
        String token = newUserToken();
        String outsiderToken = newUserToken();

        // 步骤1：先建一份只含 1 题的草稿
        String surveyId = createSurvey(token, singleQuestionSurvey("添加题目测试"));

        // 步骤2：更新草稿为四种题型
        Api updated = call(HttpMethod.PUT, "/api/surveys/" + surveyId, fourTypeSurvey("添加题目测试"), token);
        assertEquals(200, updated.status(), "步骤2 更新草稿为四种题型应返回 200，实际 " + updated.status() + " " + updated.message());
        assertEquals(4, updated.data().path("questions").size(), "步骤3 更新后应有 4 道题目");

        JsonNode questions = updated.data().path("questions");
        assertEquals("single", questions.get(0).path("type").asText(), "步骤4 第 1 题应为单选题");
        assertEquals(2, questions.get(0).path("options").size(), "步骤5 单选题应有 2 个选项");
        assertEquals("multiple", questions.get(1).path("type").asText(), "步骤6 第 2 题应为多选题");
        assertEquals(3, questions.get(1).path("options").size(), "步骤7 多选题应有 3 个选项");
        assertEquals("number", questions.get(2).path("type").asText(), "步骤8 第 3 题应为数字题");
        assertEquals(0, questions.get(2).path("options").size(), "步骤9 数字题不应有选项");
        assertEquals("text", questions.get(3).path("type").asText(), "步骤10 第 4 题应为文本题");

        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, questions.get(i).path("order").asInt(),
                    "步骤11 第 " + (i + 1) + " 题的 order 应为 " + (i + 1));
        }

        // 步骤12~15：约束保留
        assertEquals(2, questions.get(1).path("constraints").path("maxSelect").asInt(),
                "步骤12 多选题 maxSelect 应保留为 2");
        assertEquals(5000, questions.get(2).path("constraints").path("maxValue").asInt(),
                "步骤13 数字题 maxValue 应保留为 5000");
        assertTrue(questions.get(2).path("constraints").path("isInteger").asBoolean(),
                "步骤14 数字题的整数约束应保留");
        assertEquals(500, questions.get(3).path("constraints").path("maxLength").asInt(),
                "步骤15 文本题 maxLength 应保留为 500");

        // 步骤16~17：同一题内选项 ID 重复
        Map<String, Object> duplicateOption = new LinkedHashMap<>();
        duplicateOption.put("title", "选项 ID 重复");
        duplicateOption.put("questions", List.of(question("q1", 1, "single", "选项重复的题", true,
                List.of(option("same", "A", 1), option("same", "B", 2)), null, List.of())));
        Api dupOption = call(HttpMethod.PUT, "/api/surveys/" + surveyId, duplicateOption, token);
        assertEquals(400, dupOption.status(), "步骤16 同一题内选项 ID 重复应返回 400，实际 " + dupOption.status());
        assertTrue(dupOption.message().contains("选项 ID 不能重复"),
                "步骤17 应提示选项 ID 不能重复，实际: " + dupOption.message());

        // 步骤18~19：题型缺失
        Map<String, Object> noType = new LinkedHashMap<>();
        noType.put("title", "题型缺失");
        noType.put("questions", List.of(question("q1", 1, null, "没有题型的题", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of())));
        Api missingType = call(HttpMethod.PUT, "/api/surveys/" + surveyId, noType, token);
        assertEquals(400, missingType.status(), "步骤18 题型为空应返回 400，实际 " + missingType.status());
        assertTrue(missingType.message().contains("题型不能为空"),
                "步骤19 应提示题型不能为空，实际: " + missingType.message());

        // 步骤20~21：题干为空
        Map<String, Object> blankTitle = new LinkedHashMap<>();
        blankTitle.put("title", "题干为空");
        blankTitle.put("questions", List.of(question("q1", 1, "single", "", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of())));
        Api emptyTitle = call(HttpMethod.PUT, "/api/surveys/" + surveyId, blankTitle, token);
        assertEquals(400, emptyTitle.status(), "步骤20 题干为空应返回 400，实际 " + emptyTitle.status());
        assertTrue(emptyTitle.message().contains("题目标题不能为空"),
                "步骤21 应提示题目标题不能为空，实际: " + emptyTitle.message());

        // 步骤22~23：题干超长（上限 1000 字）
        Map<String, Object> longTitle = new LinkedHashMap<>();
        longTitle.put("title", "题干超长");
        longTitle.put("questions", List.of(question("q1", 1, "single", "题".repeat(1001), true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of())));
        Api overLong = call(HttpMethod.PUT, "/api/surveys/" + surveyId, longTitle, token);
        assertEquals(400, overLong.status(), "步骤22 题干超过 1000 字应返回 400，实际 " + overLong.status());
        assertTrue(overLong.message().contains("1000"),
                "步骤23 应提示 1000 字上限，实际: " + overLong.message());

        // ---------------- 第二阶段：从题库引用题目 ----------------

        // 步骤24：创建一道题库题目（单选，2 个选项）
        String[] library = createLibraryQuestion(token, "题库中的单选题", "single",
                List.of(option("lib_a", "甲", 1), option("lib_b", "乙", 2)), null);
        String libraryQuestionId = library[0];
        String libraryVersionId = library[1];

        // 步骤25：问卷引用该题，只传引用字段
        Map<String, Object> withReference = withExtraQuestion(fourTypeSurvey("添加题目测试"),
                libraryRef("q5", 5, libraryQuestionId, libraryVersionId, 1));
        Api referenced = call(HttpMethod.PUT, "/api/surveys/" + surveyId, withReference, token);
        assertEquals(200, referenced.status(), "步骤25 引用题库题目应返回 200，实际 " + referenced.status() + " " + referenced.message());
        assertEquals(5, referenced.data().path("questions").size(), "步骤26 引用后应有 5 道题目");

        // 步骤27~29：内容来自版本快照
        JsonNode ref = referenced.data().path("questions").get(4);
        assertEquals("library", ref.path("sourceType").asText(), "步骤27 引用题目的 sourceType 应为 library");
        assertEquals(libraryQuestionId, ref.path("questionId").asText(), "步骤28 应记录题库题目 ID");
        assertEquals("题库中的单选题", ref.path("title").asText(), "步骤29 题干应来自版本快照");
        assertEquals(2, ref.path("options").size(), "步骤30 选项应来自版本快照");
        assertEquals("lib_a", ref.path("options").get(0).path("id").asText(), "步骤31 选项 ID 应与版本一致");

        // 步骤32~33：引用即只读，篡改题干被拒
        Map<String, Object> tampered = new LinkedHashMap<>(libraryRef("q5", 5, libraryQuestionId, libraryVersionId, 1));
        tampered.put("title", "被篡改的题干");
        Api tamperApi = call(HttpMethod.PUT, "/api/surveys/" + surveyId,
                withExtraQuestion(fourTypeSurvey("添加题目测试"), tampered), token);
        assertEquals(400, tamperApi.status(), "步骤32 引用时篡改题干应返回 400，实际 " + tamperApi.status());
        assertTrue(tamperApi.message().contains("新版本"),
                "步骤33 应提示去题库创建新版本，实际: " + tamperApi.message());

        // 步骤34~35：引用不存在的版本
        Api missingVersion = call(HttpMethod.PUT, "/api/surveys/" + surveyId,
                withExtraQuestion(fourTypeSurvey("添加题目测试"),
                        libraryRef("q5", 5, libraryQuestionId, "no-such-version", 1)), token);
        assertEquals(400, missingVersion.status(), "步骤34 引用不存在的版本应返回 400，实际 " + missingVersion.status());
        assertTrue(missingVersion.message().contains("题目版本不存在"),
                "步骤35 应提示题目版本不存在，实际: " + missingVersion.message());

        // 步骤36~37：引用他人未共享的题目
        String[] othersLibrary = createLibraryQuestion(outsiderToken, "他人的题目", "single",
                List.of(option("x1", "X", 1), option("x2", "Y", 2)), null);
        Api unauthorized = call(HttpMethod.PUT, "/api/surveys/" + surveyId,
                withExtraQuestion(fourTypeSurvey("添加题目测试"),
                        libraryRef("q5", 5, othersLibrary[0], othersLibrary[1], 1)), token);
        assertEquals(403, unauthorized.status(), "步骤36 引用未授权题目应返回 403，实际 " + unauthorized.status());
        assertTrue(unauthorized.message().contains("无权使用该题目"),
                "步骤37 应提示无权使用该题目，实际: " + unauthorized.message());
    }

    /* ================================================================ 用例 3：跳转逻辑 */

    /**
     * 课程必测项「跳转逻辑测试」。
     *
     * <p>验证：跳转到 END、跳转到中间题并跳过必答题、数字区间跳转、多选包含跳转、
     * 未命中规则按顺序进入下一题、四类非法跳转规则在发布时被拒；
     * 并覆盖第二阶段「换绑版本时不得破坏跳转规则」。</p>
     */
    @Test
    @Order(3)
    void 用例3_跳转逻辑测试() {
        String token = newUserToken();

        // 步骤1~3：单选题命中规则跳到 END
        Map<String, Object> endSurvey = new LinkedHashMap<>();
        endSurvey.put("title", "跳转到 END");
        endSurvey.put("allowMultiple", true);
        endSurvey.put("questions", List.of(
                question("q1", 1, "single", "你喜欢户外活动吗？", true,
                        List.of(option("yes", "喜欢", 1), option("no", "不喜欢", 2)), null,
                        List.of(jump("eq", "no", null, "END"))),
                question("q2", 2, "text", "为什么不喜欢？", true, List.of(), null, List.of())));
        String endSurveyId = createSurvey(token, endSurvey);
        String endPublicId = publish(token, endSurveyId);
        String endResponseId = startResponse(token, endPublicId);

        Api jumped = answer(token, endResponseId, "q1", "no");
        assertEquals(200, jumped.status(), "步骤1 命中跳转规则应返回 200，实际 " + jumped.status());
        assertTrue(jumped.data().path("completed").asBoolean(), "步骤2 命中 END 规则应直接完成");
        assertEquals(200, submit(token, endResponseId).status(), "步骤3 被跳过的必答题 q2 不应阻止交卷");

        // 步骤4~6：未命中规则时按顺序进入下一题
        String yesResponseId = startResponse(token, endPublicId);
        Api normal = answer(token, yesResponseId, "q1", "yes");
        assertEquals("q2", normal.data().path("nextQuestion").path("id").asText(),
                "步骤4 未命中规则时应进入下一题 q2");
        assertFalse(normal.data().path("completed").asBoolean(), "步骤5 进入下一题时不应标记完成");

        // 步骤7~10：跳转到中间题目，跳过中间的必答题
        Map<String, Object> midSurvey = new LinkedHashMap<>();
        midSurvey.put("title", "跳转到中间题目");
        midSurvey.put("questions", List.of(
                question("q1", 1, "single", "有宠物吗？", true,
                        List.of(option("has", "有", 1), option("none", "没有", 2)), null,
                        List.of(jump("eq", "none", null, "q3"))),
                question("q2", 2, "text", "宠物名字", true, List.of(), null, List.of()),
                question("q3", 3, "text", "其他建议", false, List.of(), null, List.of())));
        String midSurveyId = createSurvey(token, midSurvey);
        String midPublicId = publish(token, midSurveyId);
        String midResponseId = startResponse(token, midPublicId);

        Api midJump = answer(token, midResponseId, "q1", "none");
        assertEquals("q3", midJump.data().path("nextQuestion").path("id").asText(),
                "步骤7 q1 选「没有」应直接跳到 q3");
        assertFalse(midJump.data().path("completed").asBoolean(), "步骤8 跳转后不应立即完成");

        Api midDone = answer(token, midResponseId, "q3", "无");
        assertTrue(midDone.data().path("completed").asBoolean(), "步骤9 q3 答完应标记完成");
        assertEquals(200, submit(token, midResponseId).status(),
                "步骤10 被跳过的必答题 q2 不应阻止交卷");

        // 步骤11~12：数字题按数值区间跳转
        Map<String, Object> numberSurvey = new LinkedHashMap<>();
        numberSurvey.put("title", "数字区间跳转");
        numberSurvey.put("allowMultiple", true);
        numberSurvey.put("questions", List.of(
                question("q1", 1, "number", "消费金额", true, List.of(),
                        constraints("minValue", 0, "maxValue", 10000),
                        List.of(jump("gte", null, "100", "END"))),
                question("q2", 2, "text", "补充说明", false, List.of(), null, List.of())));
        String numberSurveyId = createSurvey(token, numberSurvey);
        String numberPublicId = publish(token, numberSurveyId);
        String numberResponseId = startResponse(token, numberPublicId);
        assertTrue(answer(token, numberResponseId, "q1", 500).data().path("completed").asBoolean(),
                "步骤11 数字 500 命中 gte 100 规则，应跳到 END");

        String belowResponseId = startResponse(token, numberPublicId);
        Api below = answer(token, belowResponseId, "q1", 50);
        assertEquals("q2", below.data().path("nextQuestion").path("id").asText(),
                "步骤12 数字 50 未命中 gte 100 规则，应进入 q2");

        // 步骤13~14：多选题按包含选项跳转
        Map<String, Object> multiSurvey = new LinkedHashMap<>();
        multiSurvey.put("title", "多选包含跳转");
        multiSurvey.put("allowMultiple", true);
        multiSurvey.put("questions", List.of(
                question("q1", 1, "multiple", "你参加过哪些活动？", true,
                        List.of(option("walk", "徒步", 1), option("cycle", "骑行", 2), option("swim", "游泳", 3)),
                        null, List.of(jump("contains", "swim", null, "END"))),
                question("q2", 2, "text", "其他活动", false, List.of(), null, List.of())));
        String multiSurveyId = createSurvey(token, multiSurvey);
        String multiPublicId = publish(token, multiSurveyId);
        String multiResponseId = startResponse(token, multiPublicId);
        assertTrue(answer(token, multiResponseId, "q1", List.of("walk", "swim")).data().path("completed").asBoolean(),
                "步骤13 多选中包含「游泳」应命中 contains 规则并跳到 END");

        String multiBelowResponseId = startResponse(token, multiPublicId);
        assertEquals("q2", answer(token, multiBelowResponseId, "q1", List.of("walk")).data()
                        .path("nextQuestion").path("id").asText(),
                "步骤14 多选未包含「游泳」应进入 q2");

        // 步骤15~16：跳转目标不存在，发布时被拒
        Map<String, Object> badTarget = new LinkedHashMap<>();
        badTarget.put("title", "非法跳转目标");
        badTarget.put("questions", List.of(
                question("q1", 1, "single", "选择题", true,
                        List.of(option("a", "A", 1), option("b", "B", 2)), null,
                        List.of(jump("eq", "a", null, "not-exist"))),
                question("q2", 2, "text", "下一题", false, List.of(), null, List.of())));
        String badTargetId = createSurvey(token, badTarget);
        Api badTargetPublish = call(HttpMethod.POST, "/api/surveys/" + badTargetId + "/publish", null, token);
        assertEquals(400, badTargetPublish.status(), "步骤15 跳转目标不存在应返回 400，实际 " + badTargetPublish.status());
        assertTrue(badTargetPublish.message().contains("跳转目标题目不存在"),
                "步骤16 应提示跳转目标题目不存在，实际: " + badTargetPublish.message());

        // 步骤17~18：跳转引用当前题不存在的选项
        Map<String, Object> badOption = new LinkedHashMap<>();
        badOption.put("title", "非法跳转选项");
        badOption.put("questions", List.of(
                question("q1", 1, "single", "选择题", true,
                        List.of(option("a", "A", 1), option("b", "B", 2)), null,
                        List.of(jump("eq", "ghost", null, "END"))),
                question("q2", 2, "text", "下一题", false, List.of(), null, List.of())));
        String badOptionId = createSurvey(token, badOption);
        Api badOptionPublish = call(HttpMethod.POST, "/api/surveys/" + badOptionId + "/publish", null, token);
        assertEquals(400, badOptionPublish.status(), "步骤17 跳转引用不存在的选项应返回 400，实际 " + badOptionPublish.status());
        assertTrue(badOptionPublish.message().contains("不存在的 optionId"),
                "步骤18 应提示 optionId 不存在，实际: " + badOptionPublish.message());

        // 步骤19~20：只允许向后跳转
        Map<String, Object> backward = new LinkedHashMap<>();
        backward.put("title", "向前跳转");
        backward.put("questions", List.of(
                question("q1", 1, "text", "第一题", false, List.of(), null, List.of()),
                question("q2", 2, "single", "第二题", true,
                        List.of(option("a", "A", 1), option("b", "B", 2)), null,
                        List.of(jump("eq", "a", null, "q1")))));
        String backwardId = createSurvey(token, backward);
        Api backwardPublish = call(HttpMethod.POST, "/api/surveys/" + backwardId + "/publish", null, token);
        assertEquals(400, backwardPublish.status(), "步骤19 向前跳转应返回 400，实际 " + backwardPublish.status());
        assertTrue(backwardPublish.message().contains("只允许向后跳转"),
                "步骤20 应提示只允许向后跳转，实际: " + backwardPublish.message());

        // 步骤21~22：不支持的操作符
        Map<String, Object> badOp = new LinkedHashMap<>();
        badOp.put("title", "非法操作符");
        badOp.put("questions", List.of(
                question("q1", 1, "single", "选择题", true,
                        List.of(option("a", "A", 1), option("b", "B", 2)), null,
                        List.of(jump("between", "a", null, "END"))),
                question("q2", 2, "text", "下一题", false, List.of(), null, List.of())));
        String badOpId = createSurvey(token, badOp);
        Api badOpPublish = call(HttpMethod.POST, "/api/surveys/" + badOpId + "/publish", null, token);
        assertEquals(400, badOpPublish.status(), "步骤21 不支持的跳转操作符应返回 400，实际 " + badOpPublish.status());
        assertTrue(badOpPublish.message().contains("不支持的跳转操作符"),
                "步骤22 应提示操作符不支持，实际: " + badOpPublish.message());

        // ---------------- 第二阶段：换绑版本不得破坏跳转规则 ----------------

        // 步骤23：题库题目 v1，含两个选项
        String[] library = createLibraryQuestion(token, "跳转保护题目", "single",
                List.of(option("keep", "保留项", 1), option("drop", "将被删除的项", 2)), null);
        String libraryQuestionId = library[0];
        String v1Id = library[1];

        // 步骤24：问卷引用 v1，并配置「选 drop 则跳到 END」
        Map<String, Object> refSurvey = new LinkedHashMap<>();
        refSurvey.put("title", "换绑保护测试");
        Map<String, Object> refQuestion = libraryRef("q1", 1, libraryQuestionId, v1Id, 1);
        refQuestion.put("jumps", List.of(jump("eq", "drop", null, "END")));
        refSurvey.put("questions", List.of(refQuestion));
        String refSurveyId = createSurvey(token, refSurvey);

        // 步骤25：把题目改成 v2，用新选项替换被跳转规则引用的 drop
        // （单选题必须保留至少两个选项，所以不是删到只剩一个，而是换掉被引用的那个）
        Api v2 = call(HttpMethod.PUT, "/api/questions/" + libraryQuestionId,
                libraryQuestionPayload("跳转保护题目", "single",
                        List.of(option("keep", "保留项", 1), option("replacement", "替换项", 2)), null), token);
        assertEquals(200, v2.status(), "步骤25 更新题目应生成新版本，实际 " + v2.status() + " " + v2.message());
        assertEquals(2, v2.data().path("currentVersionNo").asInt(), "步骤26 更新后当前版本号应为 2");

        // 步骤27~28：换绑被拒
        Api rebind = call(HttpMethod.PUT,
                "/api/surveys/" + refSurveyId + "/questions/q1/version?versionNo=2", null, token);
        assertEquals(409, rebind.status(), "步骤27 v2 缺少跳转引用的选项，换绑应返回 409，实际 " + rebind.status());
        assertTrue(rebind.message().contains("跳转规则引用的选项"),
                "步骤28 应提示跳转规则引用的选项不存在，实际: " + rebind.message());

        // 步骤29~30：移除跳转规则后换绑成功
        Map<String, Object> noJumpQuestion = libraryRef("q1", 1, libraryQuestionId, v1Id, 1);
        Map<String, Object> fixedSurvey = new LinkedHashMap<>();
        fixedSurvey.put("title", "换绑保护测试");
        fixedSurvey.put("questions", List.of(noJumpQuestion));
        assertEquals(200, call(HttpMethod.PUT, "/api/surveys/" + refSurveyId, fixedSurvey, token).status(),
                "步骤29 移除跳转规则后更新草稿应成功");
        Api rebindOk = call(HttpMethod.PUT,
                "/api/surveys/" + refSurveyId + "/questions/q1/version?versionNo=2", null, token);
        assertEquals(200, rebindOk.status(), "步骤30 移除跳转规则后换绑应成功，实际 " + rebindOk.status() + " " + rebindOk.message());
        assertEquals(2, rebindOk.data().path("questions").get(0).path("versionNo").asInt(),
                "步骤31 换绑后题目版本号应为 2");
        assertEquals(2, rebindOk.data().path("questions").get(0).path("options").size(),
                "步骤32 换绑后选项应来自 v2");
        assertEquals("replacement", rebindOk.data().path("questions").get(0).path("options").get(1).path("id").asText(),
                "步骤33 换绑后选项内容应为 v2 的选项");
    }

    /* ================================================================ 用例 4：校验 */

    /**
     * 课程必测项「校验测试」。
     *
     * <p>分两组：</p>
     * <ul>
     *   <li>A 组「发布前结构校验」——题型、选项、约束、顺序、跳转规则的合法性；</li>
     *   <li>B 组「填写时答案校验」——必答、类型、选项归属、数量、数值范围、文本长度、答题顺序、越权。</li>
     * </ul>
     * <p>两组都在发布前的同一套规则上，因此用同一份问卷即可验证「合法配置能发布」。</p>
     */
    @Test
    @Order(4)
    void 用例4_校验测试() {
        String token = newUserToken();
        String outsiderToken = newUserToken();

        // ---------- A 组：发布前结构校验 ----------

        // 步骤1~2：单选题只有 1 个选项
        Map<String, Object> oneOption = new LinkedHashMap<>();
        oneOption.put("title", "单选题选项不足");
        oneOption.put("questions", List.of(question("q1", 1, "single", "只有一个选项", true,
                List.of(option("a", "A", 1)), null, List.of())));
        Api oneOptionApi = publishExpectFailure(token, createSurvey(token, oneOption));
        assertEquals(400, oneOptionApi.status(), "步骤1 单选题只有 1 个选项应返回 400，实际 " + oneOptionApi.status());
        assertTrue(oneOptionApi.message().contains("至少需要两个选项"),
                "步骤2 应提示至少需要两个选项，实际: " + oneOptionApi.message());

        // 步骤3~4：文本题带选项
        Map<String, Object> textWithOption = new LinkedHashMap<>();
        textWithOption.put("title", "文本题带选项");
        textWithOption.put("questions", List.of(question("q1", 1, "text", "文本题", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of())));
        Api textOptionApi = publishExpectFailure(token, createSurvey(token, textWithOption));
        assertEquals(400, textOptionApi.status(), "步骤3 文本题带选项应返回 400，实际 " + textOptionApi.status());
        assertTrue(textOptionApi.message().contains("不能包含选项"),
                "步骤4 应提示文本题不能包含选项，实际: " + textOptionApi.message());

        // 步骤5~6：多选 minSelect > maxSelect
        Map<String, Object> badSelect = new LinkedHashMap<>();
        badSelect.put("title", "多选约束倒置");
        badSelect.put("questions", List.of(question("q1", 1, "multiple", "多选题", true,
                List.of(option("a", "A", 1), option("b", "B", 2)),
                constraints("minSelect", 3, "maxSelect", 2), List.of())));
        Api badSelectApi = publishExpectFailure(token, createSurvey(token, badSelect));
        assertEquals(400, badSelectApi.status(), "步骤5 minSelect 大于 maxSelect 应返回 400，实际 " + badSelectApi.status());
        assertTrue(badSelectApi.message().contains("minSelect 不能大于 maxSelect"),
                "步骤6 应提示 minSelect 不能大于 maxSelect，实际: " + badSelectApi.message());

        // 步骤7~8：数字题 minValue > maxValue
        Map<String, Object> badRange = new LinkedHashMap<>();
        badRange.put("title", "数字范围倒置");
        badRange.put("questions", List.of(question("q1", 1, "number", "数字题", true, List.of(),
                constraints("minValue", 500, "maxValue", 100), List.of())));
        Api badRangeApi = publishExpectFailure(token, createSurvey(token, badRange));
        assertEquals(400, badRangeApi.status(), "步骤7 minValue 大于 maxValue 应返回 400，实际 " + badRangeApi.status());
        assertTrue(badRangeApi.message().contains("minValue 不能大于 maxValue"),
                "步骤8 应提示 minValue 不能大于 maxValue，实际: " + badRangeApi.message());

        // 步骤9~10：文本题 minLength > maxLength
        Map<String, Object> badLength = new LinkedHashMap<>();
        badLength.put("title", "文本长度倒置");
        badLength.put("questions", List.of(question("q1", 1, "text", "文本题", true, List.of(),
                constraints("minLength", 50, "maxLength", 10), List.of())));
        Api badLengthApi = publishExpectFailure(token, createSurvey(token, badLength));
        assertEquals(400, badLengthApi.status(), "步骤9 minLength 大于 maxLength 应返回 400，实际 " + badLengthApi.status());
        assertTrue(badLengthApi.message().contains("minLength 不能大于 maxLength"),
                "步骤10 应提示 minLength 不能大于 maxLength，实际: " + badLengthApi.message());

        // 步骤11~12：单选题的选择数量约束超过 1
        Map<String, Object> singleMultiSelect = new LinkedHashMap<>();
        singleMultiSelect.put("title", "单选多选约束冲突");
        singleMultiSelect.put("questions", List.of(question("q1", 1, "single", "单选题", true,
                List.of(option("a", "A", 1), option("b", "B", 2)),
                constraints("maxSelect", 2), List.of())));
        Api singleMultiApi = publishExpectFailure(token, createSurvey(token, singleMultiSelect));
        assertEquals(400, singleMultiApi.status(), "步骤11 单选题 maxSelect=2 应返回 400，实际 " + singleMultiApi.status());
        assertTrue(singleMultiApi.message().contains("不能超过 1"),
                "步骤12 应提示单选题约束不能超过 1，实际: " + singleMultiApi.message());

        // 步骤13~14：不支持的题型
        Map<String, Object> unknownType = new LinkedHashMap<>();
        unknownType.put("title", "未知题型");
        unknownType.put("questions", List.of(question("q1", 1, "ranking", "排序题", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null, List.of())));
        Api unknownTypeApi = publishExpectFailure(token, createSurvey(token, unknownType));
        assertEquals(400, unknownTypeApi.status(), "步骤13 不支持的题型应返回 400，实际 " + unknownTypeApi.status());
        assertTrue(unknownTypeApi.message().contains("不支持的题型"),
                "步骤14 应提示不支持的题型，实际: " + unknownTypeApi.message());

        // 步骤15~16：选项顺序重复
        Map<String, Object> dupOrder = new LinkedHashMap<>();
        dupOrder.put("title", "选项顺序重复");
        dupOrder.put("questions", List.of(question("q1", 1, "single", "单选题", true,
                List.of(option("a", "A", 1), option("b", "B", 1)), null, List.of())));
        Api dupOrderApi = publishExpectFailure(token, createSurvey(token, dupOrder));
        assertEquals(400, dupOrderApi.status(), "步骤15 选项顺序重复应返回 400，实际 " + dupOrderApi.status());
        assertTrue(dupOrderApi.message().contains("选项顺序必须是唯一正整数"),
                "步骤16 应提示选项顺序必须是唯一正整数，实际: " + dupOrderApi.message());

        // 步骤17~18：题目顺序重复
        Map<String, Object> dupQuestionOrder = new LinkedHashMap<>();
        dupQuestionOrder.put("title", "题目顺序重复");
        dupQuestionOrder.put("questions", List.of(
                question("q1", 1, "text", "第一题", false, List.of(), null, List.of()),
                question("q2", 1, "text", "第二题", false, List.of(), null, List.of())));
        Api dupQuestionOrderApi = publishExpectFailure(token, createSurvey(token, dupQuestionOrder));
        assertEquals(400, dupQuestionOrderApi.status(), "步骤17 题目顺序重复应返回 400，实际 " + dupQuestionOrderApi.status());
        assertTrue(dupQuestionOrderApi.message().contains("题目顺序必须为大于 0 的唯一整数"),
                "步骤18 应提示题目顺序必须唯一，实际: " + dupQuestionOrderApi.message());

        // 步骤19：合法配置可以发布
        String validSurveyId = createSurvey(token, withAllowMultiple(fourTypeSurvey("校验测试问卷")));
        String publicId = publish(token, validSurveyId);
        assertFalse(publicId.isBlank(), "步骤19 合法配置应能发布并返回公开链接 ID");

        // ---------- B 组：填写时答案校验 ----------

        // 步骤20~21：必答题传空
        String responseId = startResponse(token, publicId);
        Api emptyRequired = answer(token, responseId, "q1", null);
        assertEquals(400, emptyRequired.status(), "步骤20 必答题传空应返回 400，实际 " + emptyRequired.status());
        assertTrue(emptyRequired.message().contains("必答题，不能跳过"),
                "步骤21 应提示必答题不能跳过，实际: " + emptyRequired.message());

        // 步骤22~23：单选题类型错误
        Api singleWrongType = answer(token, responseId, "q1", List.of("q1_yes"));
        assertEquals(400, singleWrongType.status(), "步骤22 单选题传数组应返回 400，实际 " + singleWrongType.status());
        assertTrue(singleWrongType.message().contains("必须是选项 ID 字符串"),
                "步骤23 应提示必须是选项 ID 字符串，实际: " + singleWrongType.message());

        // 步骤24~25：单选题选项不属于该题
        Api singleGhostOption = answer(token, responseId, "q1", "not-an-option");
        assertEquals(400, singleGhostOption.status(), "步骤24 单选题传不存在的选项应返回 400，实际 " + singleGhostOption.status());
        assertTrue(singleGhostOption.message().contains("不属于当前题目"),
                "步骤25 应提示选项不属于当前题目，实际: " + singleGhostOption.message());

        // 步骤26：正常作答 q1
        assertEquals(200, answer(token, responseId, "q1", "q1_yes").status(), "步骤26 单选题正常作答应返回 200");

        // 步骤27~28：多选题类型错误
        Api multiWrongType = answer(token, responseId, "q2", "q2_walk");
        assertEquals(400, multiWrongType.status(), "步骤27 多选题传字符串应返回 400，实际 " + multiWrongType.status());
        assertTrue(multiWrongType.message().contains("必须是选项 ID 字符串数组"),
                "步骤28 应提示必须是选项 ID 字符串数组，实际: " + multiWrongType.message());

        // 步骤29~30：多选题重复选择
        Api multiDuplicate = answer(token, responseId, "q2", List.of("q2_walk", "q2_walk"));
        assertEquals(400, multiDuplicate.status(), "步骤29 多选重复选择应返回 400，实际 " + multiDuplicate.status());
        assertTrue(multiDuplicate.message().contains("不能重复选择同一个选项"),
                "步骤30 应提示不能重复选择，实际: " + multiDuplicate.message());

        // 步骤31~32：多选题超过 maxSelect
        Api multiOver = answer(token, responseId, "q2", List.of("q2_walk", "q2_cycle", "q2_camp"));
        assertEquals(400, multiOver.status(), "步骤31 多选超过 maxSelect 应返回 400，实际 " + multiOver.status());
        assertTrue(multiOver.message().contains("超过 maxSelect"),
                "步骤32 应提示超过 maxSelect，实际: " + multiOver.message());

        // 步骤33：正常作答 q2
        assertEquals(200, answer(token, responseId, "q2", List.of("q2_walk", "q2_camp")).status(),
                "步骤33 多选题正常作答应返回 200");

        // 步骤34~35：数字题类型错误
        Api numberWrongType = answer(token, responseId, "q3", "120");
        assertEquals(400, numberWrongType.status(), "步骤34 数字题传字符串应返回 400，实际 " + numberWrongType.status());
        assertTrue(numberWrongType.message().contains("必须是数字"),
                "步骤35 应提示必须是数字，实际: " + numberWrongType.message());

        // 步骤36~37：数字题必须为整数
        Api numberDecimal = answer(token, responseId, "q3", 12.5);
        assertEquals(400, numberDecimal.status(), "步骤36 数字题传小数应返回 400，实际 " + numberDecimal.status());
        assertTrue(numberDecimal.message().contains("只允许整数"),
                "步骤37 应提示只允许整数，实际: " + numberDecimal.message());

        // 步骤38~39：数字题小于最小值
        Api numberTooSmall = answer(token, responseId, "q3", -1);
        assertEquals(400, numberTooSmall.status(), "步骤38 数字小于 minValue 应返回 400，实际 " + numberTooSmall.status());
        assertTrue(numberTooSmall.message().contains("小于最小限制"),
                "步骤39 应提示小于最小限制，实际: " + numberTooSmall.message());

        // 步骤40~41：数字题大于最大值
        Api numberTooLarge = answer(token, responseId, "q3", 6000);
        assertEquals(400, numberTooLarge.status(), "步骤40 数字大于 maxValue 应返回 400，实际 " + numberTooLarge.status());
        assertTrue(numberTooLarge.message().contains("大于最大限制"),
                "步骤41 应提示大于最大限制，实际: " + numberTooLarge.message());

        // 步骤42：正常作答 q3
        assertEquals(200, answer(token, responseId, "q3", 120).status(), "步骤42 数字题正常作答应返回 200");

        // 步骤43~44：文本题过短
        Api textTooShort = answer(token, responseId, "q4", "好");
        assertEquals(400, textTooShort.status(), "步骤43 文本长度小于 minLength 应返回 400，实际 " + textTooShort.status());
        assertTrue(textTooShort.message().contains("未达到最小限制"),
                "步骤44 应提示未达到最小限制，实际: " + textTooShort.message());

        // 步骤45~46：文本题过长
        Api textTooLong = answer(token, responseId, "q4", "长".repeat(501));
        assertEquals(400, textTooLong.status(), "步骤45 文本长度超过 maxLength 应返回 400，实际 " + textTooLong.status());
        assertTrue(textTooLong.message().contains("超过最大限制"),
                "步骤46 应提示超过最大限制，实际: " + textTooLong.message());

        // 步骤47：正常作答 q4
        assertEquals(200, answer(token, responseId, "q4", "希望多办活动").status(), "步骤47 文本题正常作答应返回 200");

        // 步骤48~49：答题顺序错乱
        String secondResponseId = startResponse(token, publicId);
        Api outOfOrder = answer(token, secondResponseId, "q3", 100);
        assertEquals(409, outOfOrder.status(), "步骤48 跳过当前题作答应返回 409，实际 " + outOfOrder.status());
        assertTrue(outOfOrder.message().contains("答题顺序已变化"),
                "步骤49 应提示答题顺序已变化，实际: " + outOfOrder.message());

        // 步骤50~51：他人不能操作该答卷
        Api foreign = answer(token, secondResponseId, "q1", "q1_yes");
        assertEquals(200, foreign.status(), "步骤50 恢复当前题后本人作答应返回 200");
        Api otherUser = answer(outsiderToken, secondResponseId, "q2", List.of("q2_walk"));
        assertEquals(403, otherUser.status(), "步骤51 其他用户操作他人答卷应返回 403，实际 " + otherUser.status());
        assertTrue(otherUser.message().contains("无权访问该答卷会话"),
                "步骤52 应提示无权访问该答卷会话，实际: " + otherUser.message());
    }

    /** 发布问卷并断言失败，返回失败响应，供校验类断言使用。 */
    private Api publishExpectFailure(String token, String surveyId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + surveyId + "/publish", null, token);
        assertTrue(api.status() >= 400, "前置条件：该问卷发布应失败，实际 " + api.status());
        return api;
    }

    /* ================================================================ 用例 5：提交问卷 */

    /**
     * 课程必测项「提交问卷测试」。
     *
     * <p>验证：未答完不能提交、正常提交、重复提交被拒、不可重复填写、未发布/已关闭/已过截止时间三种
     * 不可填写状态、匿名答卷不落库用户 ID；并覆盖第二阶段「答卷记录题库归属与版本」
     * 与「已发布问卷禁止换绑版本」。</p>
     */
    @Test
    @Order(5)
    void 用例5_提交问卷测试() {
        String token = newUserToken();
        String surveyId = createSurvey(token, fourTypeSurvey("提交问卷测试"));
        String publicId = publish(token, surveyId);

        // 步骤1~2：未答完不能提交
        String responseId = startResponse(token, publicId);
        Api incomplete = submit(token, responseId);
        assertEquals(409, incomplete.status(), "步骤1 未答完就提交应返回 409，实际 " + incomplete.status());
        assertTrue(incomplete.message().contains("还有题目尚未完成"),
                "步骤2 应提示还有题目尚未完成，实际: " + incomplete.message());

        // 步骤3~6：逐题作答后提交
        assertEquals(200, answer(token, responseId, "q1", "q1_yes").status(), "步骤3 作答 q1 应返回 200");
        assertEquals(200, answer(token, responseId, "q2", List.of("q2_walk")).status(), "步骤4 作答 q2 应返回 200");
        assertEquals(200, answer(token, responseId, "q3", 200).status(), "步骤5 作答 q3 应返回 200");
        assertEquals(200, answer(token, responseId, "q4", "希望增加活动场次").status(), "步骤6 作答 q4 应返回 200");

        Api submitted = submit(token, responseId);
        assertEquals(200, submitted.status(), "步骤7 答完后提交应返回 200，实际 " + submitted.status() + " " + submitted.message());
        assertEquals("submitted", submitted.data().path("status").asText(), "步骤8 提交后答卷状态应为 submitted");
        assertFalse(submitted.data().path("submittedAt").isNull(), "步骤9 提交后应返回提交时间");

        // 步骤10~11：重复提交
        Api resubmit = submit(token, responseId);
        assertEquals(409, resubmit.status(), "步骤10 重复提交应返回 409，实际 " + resubmit.status());
        assertTrue(resubmit.message().contains("已经提交"),
                "步骤11 应提示已经提交，实际: " + resubmit.message());

        // 步骤12~13：allowMultiple=false 时不能重复填写
        Api restart = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token);
        assertEquals(409, restart.status(), "步骤12 不允许重复填写时再次开始应返回 409，实际 " + restart.status());
        assertTrue(restart.message().contains("已经填写并提交过"),
                "步骤13 应提示已经填写并提交过，实际: " + restart.message());

        // 步骤14~15：草稿问卷不能填写
        String draftId = createSurvey(token, fourTypeSurvey("草稿问卷"));
        String draftPublicId = call(HttpMethod.GET, "/api/surveys/" + draftId, null, token)
                .data().path("publicId").asText();
        Api draftStart = call(HttpMethod.POST, "/api/surveys/" + (draftPublicId.isBlank() ? "unknown" : draftPublicId) + "/start", null, token);
        assertTrue(draftStart.status() >= 400, "步骤14 草稿问卷（无公开链接）不应能开始答卷，实际 " + draftStart.status());

        // 步骤16~17：已关闭问卷不能填写
        String closableId = createSurvey(token, fourTypeSurvey("待关闭问卷"));
        String closablePublicId = publish(token, closableId);
        assertEquals(200, call(HttpMethod.POST, "/api/surveys/" + closableId + "/close", null, token).status(),
                "步骤15 关闭已发布问卷应返回 200");
        Api closedStart = call(HttpMethod.POST, "/api/surveys/" + closablePublicId + "/start", null, token);
        assertEquals(409, closedStart.status(), "步骤16 已关闭问卷开始答卷应返回 409，实际 " + closedStart.status());
        assertTrue(closedStart.message().contains("不可填写"),
                "步骤17 应提示问卷当前不可填写，实际: " + closedStart.message());

        // 步骤18~19：超过截止时间不能填写
        String deadlineId = createSurvey(token, fourTypeSurvey("截止时间问卷"));
        String deadlinePublicId = publish(token, deadlineId);
        // 直接把截止时间改到过去，等价于等待时间流逝，避免测试空转。
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(deadlineId)),
                new Update().set("deadline", Instant.now().minusSeconds(3600)), "surveys");
        Api expiredStart = call(HttpMethod.POST, "/api/surveys/" + deadlinePublicId + "/start", null, token);
        assertEquals(409, expiredStart.status(), "步骤18 超过截止时间开始答卷应返回 409，实际 " + expiredStart.status());
        assertTrue(expiredStart.message().contains("已超过截止时间"),
                "步骤19 应提示已超过截止时间，实际: " + expiredStart.message());

        // 步骤20~21：匿名问卷提交不落库用户 ID
        Map<String, Object> anonymousSurvey = singleQuestionSurvey("匿名提交问卷");
        anonymousSurvey.put("anonymous", true);
        String anonymousId = createSurvey(token, anonymousSurvey);
        String anonymousPublicId = publish(token, anonymousId);
        String anonymousResponseId = startResponse(token, anonymousPublicId);
        answer(token, anonymousResponseId, "q1", "o1");
        assertEquals(200, submit(token, anonymousResponseId).status(), "步骤20 匿名答卷提交应返回 200");
        Document anonymousDoc = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(anonymousResponseId)), Document.class, "responses");
        assertNotNull(anonymousDoc, "步骤21 匿名答卷应已持久化");
        assertFalse(anonymousDoc.containsKey("userId"), "步骤22 匿名答卷不应保存 userId 字段");
        assertNotNull(anonymousDoc.get("respondentKeyHash"), "步骤23 匿名答卷应保存 respondentKeyHash");

        // ---------------- 第二阶段：答卷记录题库归属与版本 ----------------

        // 步骤24：建题库题目并让问卷引用
        String[] library = createLibraryQuestion(token, "提交测试题目", "single",
                List.of(option("lib_x", "X", 1), option("lib_y", "Y", 2)), null);
        Map<String, Object> refSurvey = new LinkedHashMap<>();
        refSurvey.put("title", "题库引用提交测试");
        refSurvey.put("questions", List.of(libraryRef("q1", 1, library[0], library[1], 1)));
        String refSurveyId = createSurvey(token, refSurvey);
        String refPublicId = publish(token, refSurveyId);

        // 步骤25~27：提交并检查答卷落库字段
        String refResponseId = startResponse(token, refPublicId);
        assertEquals(200, answer(token, refResponseId, "q1", "lib_x").status(), "步骤25 引用题目作答应返回 200");
        assertEquals(200, submit(token, refResponseId).status(), "步骤26 引用题目答卷提交应返回 200");

        Document refDoc = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(refResponseId)), Document.class, "responses");
        assertNotNull(refDoc, "步骤27 答卷应已持久化");
        List<Document> answers = refDoc.getList("answers", Document.class);
        assertNotNull(answers, "步骤28 答卷应包含答案数组");
        assertEquals(1, answers.size(), "步骤29 应记录 1 条答案");
        assertEquals(library[0], answers.get(0).getString("libraryQuestionId"),
                "步骤30 答案应记录题库题目 ID");
        assertEquals(library[1], answers.get(0).getString("questionVersionId"),
                "步骤31 答案应记录题目版本 ID");

        // 步骤32~33：已发布问卷禁止换绑版本
        Api rebindPublished = call(HttpMethod.PUT,
                "/api/surveys/" + refSurveyId + "/questions/q1/version?versionNo=1", null, token);
        assertEquals(409, rebindPublished.status(), "步骤32 已发布问卷换绑版本应返回 409，实际 " + rebindPublished.status());
        assertTrue(rebindPublished.message().contains("只有草稿状态"),
                "步骤33 应提示只有草稿状态的问卷可以更换版本，实际: " + rebindPublished.message());
    }

    /* ================================================================ 用例 6：统计 */

    /**
     * 课程必测项「统计测试」。
     *
     * <p>验证：整卷统计的提交份数与逐题统计、选项计数与占比、数字平均值、文本样本、
     * 单题统计、跳转对分母的影响、越权访问统计；
     * 并覆盖第二阶段「跨问卷单题统计」——同一道题被多份问卷引用时按版本分组聚合。</p>
     */
    @Test
    @Order(6)
    void 用例6_统计测试() {
        String ownerToken = newUserToken();
        String respondentOne = newUserToken();
        String respondentTwo = newUserToken();
        String outsiderToken = newUserToken();

        // 步骤1：创建并发布四题型问卷
        String surveyId = createSurvey(ownerToken, fourTypeSurvey("统计测试问卷"));
        String publicId = publish(ownerToken, surveyId);

        // 步骤2~5：三份答卷，两份完整作答，一份在 q1 选「不喜欢」后跳转到 END
        String first = startResponse(respondentOne, publicId);
        answer(respondentOne, first, "q1", "q1_yes");
        answer(respondentOne, first, "q2", List.of("q2_walk", "q2_camp"));
        answer(respondentOne, first, "q3", 100);
        answer(respondentOne, first, "q4", "第一条建议");
        assertEquals(200, submit(respondentOne, first).status(), "步骤2 第一份答卷提交应返回 200");

        String second = startResponse(respondentTwo, publicId);
        answer(respondentTwo, second, "q1", "q1_yes");
        answer(respondentTwo, second, "q2", List.of("q2_walk"));
        answer(respondentTwo, second, "q3", 300);
        answer(respondentTwo, second, "q4", "第二条建议");
        assertEquals(200, submit(respondentTwo, second).status(), "步骤3 第二份答卷提交应返回 200");

        // 第三份用另一份问卷验证跳转影响分母：q1 选「不喜欢」直接结束，q2~q4 不计入分母
        Map<String, Object> jumpSurvey = fourTypeSurvey("统计分母测试问卷");
        @SuppressWarnings("unchecked")
        List<Object> jumpQuestions = new ArrayList<>((List<Object>) jumpSurvey.get("questions"));
        Map<String, Object> jumpQ1 = new LinkedHashMap<>(question("q1", 1, "single", "你喜欢户外活动吗？", true,
                List.of(option("q1_yes", "喜欢", 1), option("q1_no", "不喜欢", 2)), null,
                List.of(jump("eq", "q1_no", null, "END"))));
        jumpQuestions.set(0, jumpQ1);
        jumpSurvey.put("questions", jumpQuestions);
        String jumpSurveyId = createSurvey(ownerToken, jumpSurvey);
        String jumpPublicId = publish(ownerToken, jumpSurveyId);

        String third = startResponse(respondentOne, jumpPublicId);
        assertTrue(answer(respondentOne, third, "q1", "q1_no").data().path("completed").asBoolean(),
                "步骤4 第三份答卷在 q1 命中跳转规则直接结束");
        assertEquals(200, submit(respondentOne, third).status(), "步骤5 第三份答卷提交应返回 200");

        // 步骤6~7：整卷统计
        Api stats = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats", null, ownerToken);
        assertEquals(200, stats.status(), "步骤6 整卷统计应返回 200，实际 " + stats.status());
        assertEquals(2, stats.data().path("submittedCount").asLong(), "步骤7 已提交份数应为 2");

        // 步骤8~10：单选题选项计数
        JsonNode q1Stats = stats.data().path("questions").get(0);
        assertEquals(2, q1Stats.path("answeredCount").asLong(), "步骤8 q1 回答人数应为 2");
        assertEquals(2, q1Stats.path("options").get(0).path("count").asLong(), "步骤9 选项「喜欢」计数应为 2");
        assertEquals(0, q1Stats.path("options").get(1).path("count").asLong(), "步骤10 选项「不喜欢」计数应为 0");

        // 步骤11~13：多选题计数
        JsonNode q2Stats = stats.data().path("questions").get(1);
        assertEquals(2, q2Stats.path("answeredCount").asLong(), "步骤11 q2 回答人数应为 2");
        assertEquals(2, q2Stats.path("options").get(0).path("count").asLong(), "步骤12 多选「徒步」计数应为 2");
        assertEquals(0, q2Stats.path("options").get(1).path("count").asLong(), "步骤13 多选「骑行」计数应为 0");
        assertEquals(1, q2Stats.path("options").get(2).path("count").asLong(), "步骤14 多选「露营」计数应为 1");

        // 步骤15~16：数字题平均值
        JsonNode q3Stats = stats.data().path("questions").get(2);
        assertEquals(200.0, q3Stats.path("averageNumber").asDouble(), 0.0001, "步骤15 数字题平均值应为 (100+300)/2=200");
        assertEquals(2, q3Stats.path("answeredCount").asLong(), "步骤16 数字题回答人数应为 2");

        // 步骤17~18：文本题样本
        JsonNode q4Stats = stats.data().path("questions").get(3);
        assertEquals(2, q4Stats.path("textAnswers").size(), "步骤17 文本题应返回 2 条内容");
        assertEquals("第一条建议", q4Stats.path("textAnswers").get(0).asText(), "步骤18 文本内容应与提交一致");

        // 步骤19~20：单题统计
        Api single = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/q3", null, ownerToken);
        assertEquals(200, single.status(), "步骤19 单题统计应返回 200，实际 " + single.status());
        assertEquals("q3", single.data().path("questionId").asText(), "步骤20 单题统计应返回指定题目");
        assertEquals(200.0, single.data().path("averageNumber").asDouble(), 0.0001, "步骤21 单题统计平均值应为 200");

        // 步骤22~23：单题统计的异常与越权
        assertEquals(404, call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/not-exist", null, ownerToken).status(),
                "步骤22 不存在的题目单题统计应返回 404");
        assertEquals(403, call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/q1", null, outsiderToken).status(),
                "步骤23 他人查看统计应返回 403");

        // 步骤24~25：跳转影响分母
        Api jumpStats = call(HttpMethod.GET, "/api/surveys/" + jumpSurveyId + "/stats", null, ownerToken);
        assertEquals(1, jumpStats.data().path("submittedCount").asLong(), "步骤24 跳转问卷已提交份数应为 1");
        assertEquals(1, jumpStats.data().path("questions").get(0).path("answeredCount").asLong(),
                "步骤25 q1 回答人数应为 1");
        assertEquals(0, jumpStats.data().path("questions").get(1).path("answeredCount").asLong(),
                "步骤26 被跳转跳过的 q2 回答人数应为 0，分母不因整卷份数而虚增");

        // ---------------- 第二阶段：跨问卷单题统计 ----------------

        // 步骤27：建题库题目 v1
        String[] library = createLibraryQuestion(ownerToken, "跨问卷统计题目", "single",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null);
        String libraryQuestionId = library[0];
        String v1Id = library[1];

        // 步骤28~30：问卷一引用 v1 并收集 1 份答卷
        Map<String, Object> surveyOne = new LinkedHashMap<>();
        surveyOne.put("title", "跨问卷统计一");
        surveyOne.put("questions", List.of(libraryRef("q1", 1, libraryQuestionId, v1Id, 1)));
        String surveyOneId = createSurvey(ownerToken, surveyOne);
        String surveyOnePublicId = publish(ownerToken, surveyOneId);
        String oneResponse = startResponse(respondentOne, surveyOnePublicId);
        answer(respondentOne, oneResponse, "q1", "c1");
        assertEquals(200, submit(respondentOne, oneResponse).status(), "步骤27 问卷一答卷提交应返回 200");

        // 步骤31：把题目改成 v2（新增一个选项）
        Api v2Api = call(HttpMethod.PUT, "/api/questions/" + libraryQuestionId,
                libraryQuestionPayload("跨问卷统计题目", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2), option("c3", "选项三", 3)), null),
                ownerToken);
        assertEquals(200, v2Api.status(), "步骤28 更新题目应生成 v2，实际 " + v2Api.status() + " " + v2Api.message());
        // accessLevel 是前端裁剪「修改 / 归档 / 共享授权」按钮的唯一依据，这里顺带守住它：
        // 创建者必须是 owner，否则页面会把创建者当成被授权者，按钮全被藏起来。
        assertEquals("owner", v2Api.data().path("accessLevel").asText(), "步骤28 创建者查自己题目的 accessLevel 应为 owner");
        String v2Id = v2Api.data().path("currentVersion").path("id").asText();

        // 步骤32~34：问卷二引用 v2 并收集 1 份答卷
        Map<String, Object> surveyTwo = new LinkedHashMap<>();
        surveyTwo.put("title", "跨问卷统计二");
        surveyTwo.put("questions", List.of(libraryRef("q1", 1, libraryQuestionId, v2Id, 2)));
        String surveyTwoId = createSurvey(ownerToken, surveyTwo);
        String surveyTwoPublicId = publish(ownerToken, surveyTwoId);
        String twoResponse = startResponse(respondentTwo, surveyTwoPublicId);
        answer(respondentTwo, twoResponse, "q1", "c2");
        assertEquals(200, submit(respondentTwo, twoResponse).status(), "步骤29 问卷二答卷提交应返回 200");

        // 步骤35~40：跨问卷统计按版本分组
        Api crossStats = call(HttpMethod.GET, "/api/questions/" + libraryQuestionId + "/stats", null, ownerToken);
        assertEquals(200, crossStats.status(), "步骤30 跨问卷统计应返回 200，实际 " + crossStats.status() + " " + crossStats.message());
        assertEquals(2, crossStats.data().path("surveyCount").asInt(), "步骤31 引用该题的问卷数应为 2");
        assertEquals(2, crossStats.data().path("versionCount").asInt(), "步骤32 该题版本数应为 2");
        assertEquals(2, crossStats.data().path("totalAnswered").asLong(), "步骤33 合计回答数应为 2");

        JsonNode versions = crossStats.data().path("versions");
        assertEquals(2, versions.size(), "步骤34 统计结果应按版本分成 2 组");

        JsonNode v1Stats = null;
        JsonNode v2Stats = null;
        for (JsonNode versionNode : versions) {
            if (versionNode.path("versionNo").asInt() == 1) {
                v1Stats = versionNode;
            } else if (versionNode.path("versionNo").asInt() == 2) {
                v2Stats = versionNode;
            }
        }
        assertNotNull(v1Stats, "步骤35 统计结果应包含 v1 分组");
        assertNotNull(v2Stats, "步骤36 统计结果应包含 v2 分组");

        assertEquals(1, v1Stats.path("answeredCount").asLong(), "步骤37 v1 的回答数应为 1");
        assertEquals(1, v1Stats.path("options").get(0).path("count").asLong(), "步骤38 v1 中选项一被选 1 次");
        assertEquals(0, v1Stats.path("options").get(1).path("count").asLong(), "步骤39 v1 中选项二被选 0 次");

        assertEquals(1, v2Stats.path("answeredCount").asLong(), "步骤40 v2 的回答数应为 1");
        assertEquals(1, v2Stats.path("options").get(1).path("count").asLong(), "步骤41 v2 中选项二被选 1 次");
        assertEquals(0, v2Stats.path("options").get(2).path("count").asLong(), "步骤42 v2 中选项三被选 0 次");

        // 步骤43~44：跨问卷统计的权限边界
        Api forbidden = call(HttpMethod.GET, "/api/questions/" + libraryQuestionId + "/stats", null, outsiderToken);
        assertEquals(403, forbidden.status(), "步骤43 非创建者查看跨问卷统计应返回 403，实际 " + forbidden.status());
        assertTrue(forbidden.message().contains("只有题目创建者"),
                "步骤44 应提示只有题目创建者可以查看，实际: " + forbidden.message());

        // 步骤45~46：使用情况反查
        Api usages = call(HttpMethod.GET, "/api/questions/" + libraryQuestionId + "/usages", null, ownerToken);
        assertEquals(200, usages.status(), "步骤45 使用情况查询应返回 200，实际 " + usages.status());
        assertEquals(2, usages.data().size(), "步骤46 该题应被 2 份问卷引用");
    }

    /* ==================================================================== 数据清理 */

    /** 清理本次测试产生的全部数据，包括第二阶段新增的三个集合，不影响项目原有数据。 */
    @AfterAll
    void cleanUpTestData() {
        if (createdUsernames.isEmpty()) {
            return;
        }
        List<Document> users = mongoTemplate.find(
                Query.query(Criteria.where("username").in(createdUsernames)), Document.class, "users");
        if (users.isEmpty()) {
            return;
        }
        List<ObjectId> userObjectIds = users.stream().map(user -> user.getObjectId("_id")).toList();
        List<String> userIds = userObjectIds.stream().map(ObjectId::toHexString).toList();

        List<Document> surveys = mongoTemplate.find(
                Query.query(Criteria.where("ownerId").in(userIds)), Document.class, "surveys");
        List<String> surveyIds = surveys.stream().map(survey -> survey.get("_id").toString()).toList();
        if (!surveyIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("surveyId").in(surveyIds)), "responses");
            mongoTemplate.remove(Query.query(Criteria.where("_id").in(surveyIds)), "surveys");
        }

        List<Document> questions = mongoTemplate.find(
                Query.query(Criteria.where("ownerId").in(userIds)), Document.class, "questions");
        List<String> questionIds = questions.stream().map(question -> question.get("_id").toString()).toList();
        if (!questionIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("questionId").in(questionIds)), "question_versions");
            mongoTemplate.remove(Query.query(Criteria.where("questionId").in(questionIds)), "question_shares");
            mongoTemplate.remove(Query.query(Criteria.where("_id").in(questionIds)), "questions");
        }

        // 被授权方若在本次测试中被授权，也会留下指向他人题目的共享记录。
        mongoTemplate.remove(Query.query(Criteria.where("granteeId").in(userIds)), "question_shares");
        mongoTemplate.remove(Query.query(Criteria.where("_id").in(userObjectIds)), "users");
    }
}
