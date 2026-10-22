package com.example.surveysystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
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
 * 第一阶段需求验收测试（真实启动 Web 容器 + 真实 MongoDB）。
 *
 * <p>覆盖需求「六、测试要求」列出的六类用例：
 * 创建问卷、添加题目、跳转逻辑、校验、提交问卷、统计。
 * 每个用例自建独立用户与问卷，结束后按用户名前缀清理测试数据，不影响已有数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SurveyFlowIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsernames = new ArrayList<>();

    /* ==================== 基础设施 ==================== */

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
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = rest.exchange(path, method, entity, String.class);
        JsonNode json;
        try {
            String text = response.getBody();
            json = (text == null || text.isBlank()) ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), ex);
        }
        return new Api(response.getStatusCode().value(), json);
    }

    /** 发送指定 Content-Type 的原始请求体，用于验证 415 与 JSON 语法错误。 */
    private Api callRaw(HttpMethod method, String path, String rawBody, String contentType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = rest.exchange(path, method, new HttpEntity<>(rawBody, headers), String.class);
        JsonNode json;
        try {
            String text = response.getBody();
            json = (text == null || text.isBlank()) ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), ex);
        }
        return new Api(response.getStatusCode().value(), json);
    }

    /** 注册并登录一个新用户，返回 token。 */
    private String newUserToken() {
        String username = "itest_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsernames.add(username);
        Api register = call(HttpMethod.POST, "/api/auth/register",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(200, register.status(), "注册应成功: " + register.message());
        // 回归断言：注册返回的令牌必须能直接访问受保护接口。
        // 早期版本注册与登录各自拼装令牌载荷，导致注册后的令牌无法通过校验。
        String registerToken = register.data().path("token").asText();
        assertFalse(registerToken.isBlank(), "注册应返回 token");
        Api meByRegisterToken = call(HttpMethod.GET, "/api/surveys/mine", null, registerToken);
        assertEquals(200, meByRegisterToken.status(), "注册返回的令牌应可直接访问受保护接口");
        Api login = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(200, login.status(), "登录应成功: " + login.message());
        String token = login.data().path("token").asText();
        assertFalse(token.isBlank(), "登录应返回 token");
        return token;
    }

    /** 标准四题型问卷：单选 + 多选（1~2）+ 数字（0~5000 整数）+ 文本（≤500 字）。 */
    private Map<String, Object> standardSurvey(String title) {
        Map<String, Object> survey = new LinkedHashMap<>();
        survey.put("title", title);
        survey.put("description", "集成测试问卷");
        survey.put("anonymous", false);
        survey.put("allowMultiple", false);
        List<Object> questions = new ArrayList<>();
        questions.add(question("q1", 1, "single", "你喜欢户外活动吗？", true,
                List.of(option("q1_yes", "喜欢", 1), option("q1_no", "不喜欢", 2)),
                null,
                List.of(jump("eq", "q1_no", null, "END"))));
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

    private Map<String, Object> question(String id, int order, String type, String title, boolean required,
                                         List<? extends Map<String, ?>> options,
                                         Map<String, ?> constraints,
                                         List<? extends Map<String, ?>> jumps) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("id", id);
        q.put("order", order);
        q.put("type", type);
        q.put("title", title);
        q.put("required", required);
        List<Object> optionList = new ArrayList<>();
        options.forEach(item -> optionList.add(new LinkedHashMap<String, Object>(item)));
        q.put("options", optionList);
        if (constraints != null) {
            q.put("constraints", new LinkedHashMap<String, Object>(constraints));
        }
        List<Object> jumpList = new ArrayList<>();
        jumps.forEach(item -> jumpList.add(new LinkedHashMap<String, Object>(item)));
        q.put("jumps", jumpList);
        return q;
    }

    private Map<String, Object> option(String id, String label, int order) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        option.put("order", order);
        return option;
    }

    private Map<String, Object> constraints(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private Map<String, Object> jump(String op, String optionId, String value, String targetQuestionId) {
        Map<String, Object> jump = new LinkedHashMap<>();
        jump.put("op", op);
        if (optionId != null) {
            jump.put("optionId", optionId);
        }
        if (value != null) {
            jump.put("value", value);
        }
        jump.put("targetQuestionId", targetQuestionId);
        return jump;
    }

    @SuppressWarnings("unchecked")
    private List<Object> questionsOf(Map<String, Object> survey) {
        return (List<Object>) survey.get("questions");
    }

    private String createSurvey(String token, Map<String, Object> payload) {
        Api api = call(HttpMethod.POST, "/api/surveys", payload, token);
        assertEquals(200, api.status(), "创建问卷应成功: " + api.message());
        return api.data().path("id").asText();
    }

    private String publish(String token, String surveyId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + surveyId + "/publish", null, token);
        assertEquals(200, api.status(), "发布问卷应成功: " + api.message());
        return api.data().path("publicId").asText();
    }

    private Api answer(String token, String responseId, String questionId, Object answer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questionId", questionId);
        body.put("answer", answer);
        return call(HttpMethod.POST, "/api/responses/" + responseId + "/answer", body, token);
    }

    /* ==================== 用例 1：用户账号与权限隔离 ==================== */

    @Test
    @Order(1)
    void 用例1_注册登录与数据隔离() {
        String username = "itest_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsernames.add(username);

        Api register = call(HttpMethod.POST, "/api/auth/register",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(200, register.status(), "步骤1 注册新账号应返回 200");

        Api duplicate = call(HttpMethod.POST, "/api/auth/register",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(409, duplicate.status(), "步骤2 重复注册同名账号应返回 409");

        Api weak = call(HttpMethod.POST, "/api/auth/register",
                Map.of("username", "ab", "password", "123"), null);
        assertEquals(400, weak.status(), "步骤3 用户名过短/密码过短应返回 400");

        Api wrongPassword = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", "wrong-password"), null);
        assertEquals(401, wrongPassword.status(), "步骤4 错误密码登录应返回 401");

        Api noToken = call(HttpMethod.GET, "/api/surveys/mine", null, null);
        assertEquals(401, noToken.status(), "步骤5 无令牌访问受保护接口应返回 401");

        Api badToken = call(HttpMethod.GET, "/api/surveys/mine", null, "not-a-real-token");
        assertEquals(401, badToken.status(), "步骤6 伪造令牌应返回 401");

        // 数据隔离：A 创建的问卷不出现在 B 的列表里，B 也读不到 A 的问卷
        String tokenA = newUserToken();
        String tokenB = newUserToken();
        String surveyId = createSurvey(tokenA, standardSurvey("隔离测试问卷"));

        Api mineB = call(HttpMethod.GET, "/api/surveys/mine", null, tokenB);
        assertEquals(200, mineB.status());
        boolean leaked = false;
        for (JsonNode node : mineB.data()) {
            if (surveyId.equals(node.path("id").asText())) {
                leaked = true;
            }
        }
        assertFalse(leaked, "步骤7 B 的问卷列表不应包含 A 的问卷");

        Api crossRead = call(HttpMethod.GET, "/api/surveys/" + surveyId, null, tokenB);
        assertEquals(403, crossRead.status(), "步骤8 他人读取问卷应返回 403");

        Api crossStats = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats", null, tokenB);
        assertEquals(403, crossStats.status(), "步骤9 他人查看统计应返回 403");
    }

    /* ==================== 用例 2：创建问卷与添加题目 ==================== */

    @Test
    @Order(2)
    void 用例2_创建问卷与添加多种题型() {
        String token = newUserToken();

        Api created = call(HttpMethod.POST, "/api/surveys", standardSurvey("题型测试问卷"), token);
        assertEquals(200, created.status(), "步骤1 创建问卷应返回 200");
        String surveyId = created.data().path("id").asText();
        assertFalse(surveyId.isBlank(), "步骤2 应返回问卷 ID");
        assertEquals("draft", created.data().path("status").asText(), "步骤3 新建问卷状态应为 draft");
        assertEquals(4, created.data().path("questions").size(), "步骤4 应保存 4 道题目");
        assertEquals("single", created.data().path("questions").get(0).path("type").asText(),
                "步骤5 第 1 题应为单选");
        assertEquals("multiple", created.data().path("questions").get(1).path("type").asText(),
                "步骤6 第 2 题应为多选");
        assertEquals("number", created.data().path("questions").get(2).path("type").asText(),
                "步骤7 第 3 题应为数字填空");
        assertEquals("text", created.data().path("questions").get(3).path("type").asText(),
                "步骤8 第 4 题应为文本填空");

        Api mine = call(HttpMethod.GET, "/api/surveys/mine", null, token);
        boolean found = false;
        for (JsonNode node : mine.data()) {
            if (surveyId.equals(node.path("id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "步骤9 我的问卷列表应包含新建问卷");

        Api detail = call(HttpMethod.GET, "/api/surveys/" + surveyId, null, token);
        assertEquals(200, detail.status(), "步骤10 查看自己创建的问卷应返回 200");

        Map<String, Object> updated = standardSurvey("题型测试问卷-已修改");
        Api update = call(HttpMethod.PUT, "/api/surveys/" + surveyId, updated, token);
        assertEquals(200, update.status(), "步骤11 修改草稿应返回 200");
        assertEquals("题型测试问卷-已修改", update.data().path("title").asText(), "步骤12 标题应被更新");

        Api blankTitle = call(HttpMethod.POST, "/api/surveys",
                Map.of("title", "   ", "questions", List.of(question("q1", 1, "text", "题", true,
                        List.of(), null, List.of()))), token);
        assertEquals(400, blankTitle.status(), "步骤13 空白标题应返回 400");

        Api emptyQuestions = call(HttpMethod.POST, "/api/surveys",
                Map.of("title", "没有题目的问卷", "questions", List.of()), token);
        assertEquals(400, emptyQuestions.status(), "步骤14 题目为空应返回 400");
    }

    /* ==================== 用例 3：发布前校验 ==================== */

    @Test
    @Order(3)
    void 用例3_发布前校验非法配置() {
        String token = newUserToken();

        // 选项不足两个
        Map<String, Object> oneOption = standardSurvey("选项不足");
        questionsOf(oneOption).set(0, question("q1", 1, "single", "单选", true,
                List.of(option("only", "唯一选项", 1)), null, List.of()));
        String id1 = createSurvey(token, oneOption);
        assertEquals(400, call(HttpMethod.POST, "/api/surveys/" + id1 + "/publish", null, token).status(),
                "步骤1 单选题只有一个选项时发布应返回 400");

        // 逆向跳转
        Map<String, Object> backward = standardSurvey("逆向跳转");
        questionsOf(backward).set(1, question("q2", 2, "multiple", "多选", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null,
                List.of(jump("eq", "a", null, "q1"))));
        String id2 = createSurvey(token, backward);
        assertEquals(400, call(HttpMethod.POST, "/api/surveys/" + id2 + "/publish", null, token).status(),
                "步骤2 逆向跳转发布应返回 400");

        // 未知题型
        Map<String, Object> unknownType = standardSurvey("未知题型");
        questionsOf(unknownType).set(0, question("q1", 1, "rating", "打分题", true, List.of(), null, List.of()));
        String id3 = createSurvey(token, unknownType);
        assertEquals(400, call(HttpMethod.POST, "/api/surveys/" + id3 + "/publish", null, token).status(),
                "步骤3 未知题型发布应返回 400");

        // 非法跳转操作符
        Map<String, Object> badOp = standardSurvey("非法操作符");
        questionsOf(badOp).set(0, question("q1", 1, "single", "单选", true,
                List.of(option("a", "A", 1), option("b", "B", 2)), null,
                List.of(jump("like", "a", null, "END"))));
        String id4 = createSurvey(token, badOp);
        assertEquals(400, call(HttpMethod.POST, "/api/surveys/" + id4 + "/publish", null, token).status(),
                "步骤4 非法跳转操作符发布应返回 400");

        // 合法配置可以发布
        String id5 = createSurvey(token, standardSurvey("可发布问卷"));
        Api publish = call(HttpMethod.POST, "/api/surveys/" + id5 + "/publish", null, token);
        assertEquals(200, publish.status(), "步骤5 合法问卷发布应返回 200");
        assertFalse(publish.data().path("publicId").asText().isBlank(), "步骤6 发布后应生成 publicId");

        assertEquals(409, call(HttpMethod.POST, "/api/surveys/" + id5 + "/publish", null, token).status(),
                "步骤7 重复发布应返回 409");
        assertEquals(409, call(HttpMethod.PUT, "/api/surveys/" + id5, standardSurvey("改已发布"), token).status(),
                "步骤8 修改已发布问卷应返回 409（状态冲突）");
        assertEquals(409, call(HttpMethod.DELETE, "/api/surveys/" + id5, null, token).status(),
                "步骤9 删除已发布问卷应返回 409");
    }

    /* ==================== 用例 4：答题校验 ==================== */

    @Test
    @Order(4)
    void 用例4_填写时的逐题校验() {
        String token = newUserToken();
        String surveyId = createSurvey(token, standardSurvey("校验测试问卷"));
        String publicId = publish(token, surveyId);

        Api start = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token);
        assertEquals(200, start.status(), "步骤1 开始答题应返回 200");
        String responseId = start.data().path("responseId").asText();
        assertEquals("q1", start.data().path("question").path("id").asText(), "步骤2 首题应为 q1");

        assertEquals(409, answer(token, responseId, "q3", 1).status(),
                "步骤3 提交非当前题应返回 409（答题顺序冲突）");
        assertEquals(400, answer(token, responseId, "q1", "not-exist").status(),
                "步骤4 非法选项应返回 400");
        assertEquals(400, answer(token, responseId, "q1", null).status(),
                "步骤5 必答题跳过应返回 400");

        Api toQ2 = answer(token, responseId, "q1", "q1_yes");
        assertEquals(200, toQ2.status(), "步骤6 正常作答应返回 200");
        assertEquals("q2", toQ2.data().path("nextQuestion").path("id").asText(), "步骤7 应进入 q2");

        assertEquals(400, answer(token, responseId, "q2", List.of()).status(),
                "步骤8 多选传空数组应返回 400（空数组视为未作答，该题为必答题）");
        assertEquals(400, answer(token, responseId, "q2", List.of("q2_walk", "q2_cycle", "q2_camp")).status(),
                "步骤9 多选超过 maxSelect 应返回 400");
        assertEquals(400, answer(token, responseId, "q2", List.of("q2_walk", "q2_walk")).status(),
                "步骤10 多选重复选择同一项应返回 400");

        Api toQ3 = answer(token, responseId, "q2", List.of("q2_walk", "q2_camp"));
        assertEquals("q3", toQ3.data().path("nextQuestion").path("id").asText(), "步骤11 应进入 q3");

        assertEquals(400, answer(token, responseId, "q3", 6000).status(),
                "步骤12 数字大于上限应返回 400");
        assertEquals(400, answer(token, responseId, "q3", -1).status(),
                "步骤13 数字小于下限应返回 400");
        assertEquals(400, answer(token, responseId, "q3", 12.5).status(),
                "步骤14 isInteger 题传小数应返回 400");
        assertEquals(400, answer(token, responseId, "q3", "120").status(),
                "步骤15 数字题传字符串应返回 400");

        Api toQ4 = answer(token, responseId, "q3", 120);
        assertEquals("q4", toQ4.data().path("nextQuestion").path("id").asText(), "步骤16 应进入 q4");

        assertEquals(400, answer(token, responseId, "q4", "x").status(),
                "步骤17 文本短于 minLength 应返回 400");
        assertEquals(400, answer(token, responseId, "q4", "x".repeat(501)).status(),
                "步骤18 文本超过 maxLength 应返回 400");

        Api done = answer(token, responseId, "q4", "希望增加亲子活动");
        assertEquals(200, done.status(), "步骤19 最后一题作答应返回 200");
        assertTrue(done.data().path("completed").asBoolean(), "步骤20 应标记 completed=true");

        // 步骤21-22 多选 minSelect 下限：要求至少选 2 项，只提交 1 项
        Map<String, Object> minPayload = new LinkedHashMap<>();
        minPayload.put("title", "多选下限测试");
        minPayload.put("questions", List.of(question("q1", 1, "multiple", "至少选两项", true,
                List.of(option("o1", "A", 1), option("o2", "B", 2), option("o3", "C", 3)),
                constraints("minSelect", 2, "maxSelect", 3), List.of())));
        String minSurveyId = createSurvey(token, minPayload);
        String minPublicId = publish(token, minSurveyId);
        String minResponseId = call(HttpMethod.POST, "/api/surveys/" + minPublicId + "/start", null, token)
                .data().path("responseId").asText();
        Api belowMin = answer(token, minResponseId, "q1", List.of("o1"));
        assertEquals(400, belowMin.status(), "步骤21 多选只选 1 项（少于 minSelect=2）应返回 400");
        assertTrue(belowMin.message().contains("minSelect"),
                "步骤22 提示应指明 minSelect 限制，实际: " + belowMin.message());
    }

    /* ==================== 用例 5：跳转逻辑 ==================== */

    @Test
    @Order(5)
    void 用例5_条件跳转逻辑() {
        String token = newUserToken();

        // 跳转到 END
        String surveyId = createSurvey(token, standardSurvey("跳转到END"));
        String publicId = publish(token, surveyId);
        String responseId = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token)
                .data().path("responseId").asText();

        Api jumped = answer(token, responseId, "q1", "q1_no");
        assertEquals(200, jumped.status(), "步骤1 命中跳转规则应返回 200");
        assertTrue(jumped.data().path("completed").asBoolean(), "步骤2 命中 END 规则应直接完成");

        Api submit = call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token);
        assertEquals(200, submit.status(), "步骤3 被跳过的必答题不应阻塞交卷: " + submit.message());

        // 跳转到中间题目：q1 选「没有」直接跳到 q3，q2（必答）被跳过
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "中段跳转问卷");
        List<Object> qs = new ArrayList<>();
        qs.add(question("q1", 1, "single", "有宠物吗？", true,
                List.of(option("yes", "有", 1), option("no", "没有", 2)), null,
                List.of(jump("eq", "no", null, "q3"))));
        qs.add(question("q2", 2, "text", "宠物名字", true, List.of(), null, List.of()));
        qs.add(question("q3", 3, "text", "其他建议", false, List.of(), null, List.of()));
        payload.put("questions", qs);
        String id2 = createSurvey(token, payload);
        String pid2 = publish(token, id2);
        String rid2 = call(HttpMethod.POST, "/api/surveys/" + pid2 + "/start", null, token)
                .data().path("responseId").asText();

        Api mid = answer(token, rid2, "q1", "no");
        assertEquals("q3", mid.data().path("nextQuestion").path("id").asText(),
                "步骤4 q1 选「没有」应直接跳到 q3");
        assertFalse(mid.data().path("completed").asBoolean(), "步骤5 跳转后不应立即完成");

        Api finish = answer(token, rid2, "q3", "无");
        assertTrue(finish.data().path("completed").asBoolean(), "步骤6 q3 答完应完成");
        assertEquals(200, call(HttpMethod.POST, "/api/responses/" + rid2 + "/submit", null, token).status(),
                "步骤7 被跳过的必答题 q2 不应阻塞交卷");

        // 数字题跳转：>= 100 跳到 END
        Map<String, Object> numPayload = new LinkedHashMap<>();
        numPayload.put("title", "数字跳转问卷");
        List<Object> numQs = new ArrayList<>();
        numQs.add(question("q1", 1, "number", "消费金额", true, List.of(),
                constraints("minValue", 0, "maxValue", 10000),
                List.of(jump("gte", null, "100", "END"))));
        numQs.add(question("q2", 2, "text", "补充说明", false, List.of(), null, List.of()));
        numPayload.put("questions", numQs);
        String id3 = createSurvey(token, numPayload);
        String pid3 = publish(token, id3);
        String rid3 = call(HttpMethod.POST, "/api/surveys/" + pid3 + "/start", null, token)
                .data().path("responseId").asText();
        Api numJump = answer(token, rid3, "q1", 500);
        assertTrue(numJump.data().path("completed").asBoolean(),
                "步骤8 数字题命中 gte 规则应跳到 END");
    }

    /* ==================== 用例 6：提交问卷与统计 ==================== */

    @Test
    @Order(6)
    void 用例6_提交问卷与统计结果() {
        String token = newUserToken();
        String surveyId = createSurvey(token, standardSurvey("统计测试问卷"));
        String publicId = publish(token, surveyId);

        String responseId = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token)
                .data().path("responseId").asText();

        assertEquals(409, call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token).status(),
                "步骤1 一题未答直接交卷应被拒绝");

        answer(token, responseId, "q1", "q1_yes");
        answer(token, responseId, "q2", List.of("q2_walk", "q2_camp"));
        answer(token, responseId, "q3", 120);
        answer(token, responseId, "q4", "希望多办活动");

        Api submit = call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token);
        assertEquals(200, submit.status(), "步骤2 交卷应返回 200");
        assertEquals("submitted", submit.data().path("status").asText(), "步骤3 答卷状态应为 submitted");

        assertEquals(409, call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token).status(),
                "步骤4 重复交卷应返回 409");
        assertEquals(409, call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token).status(),
                "步骤5 allowMultiple=false 时同一人不能再次填写");

        Api stats = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats", null, token);
        assertEquals(200, stats.status(), "步骤6 整卷统计应返回 200");
        assertEquals(1, stats.data().path("submittedCount").asLong(), "步骤7 已提交份数应为 1");

        JsonNode q1 = stats.data().path("questions").get(0);
        assertEquals(1, q1.path("answeredCount").asLong(), "步骤8 q1 回答人数应为 1");
        assertEquals(1, q1.path("options").get(0).path("count").asLong(), "步骤9 q1 选项「喜欢」计数应为 1");
        assertEquals(0, q1.path("options").get(1).path("count").asLong(), "步骤10 q1 选项「不喜欢」计数应为 0");

        JsonNode q2 = stats.data().path("questions").get(1);
        assertEquals(1, q2.path("options").get(0).path("count").asLong(), "步骤11 多选「徒步」计数应为 1");
        assertEquals(0, q2.path("options").get(1).path("count").asLong(), "步骤12 多选「骑行」计数应为 0");
        assertEquals(1, q2.path("options").get(2).path("count").asLong(), "步骤13 多选「露营」计数应为 1");

        JsonNode q3 = stats.data().path("questions").get(2);
        assertEquals(120.0, q3.path("averageNumber").asDouble(), 0.0001, "步骤14 数字题平均值应为 120.0");

        JsonNode q4 = stats.data().path("questions").get(3);
        assertEquals(1, q4.path("textAnswers").size(), "步骤15 文本题应返回 1 条内容");
        assertEquals("希望多办活动", q4.path("textAnswers").get(0).asText(), "步骤16 文本内容应一致");

        // 单题统计接口（需求「查看某一道题的统计」）
        Api single = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/q3", null, token);
        assertEquals(200, single.status(), "步骤17 单题统计应返回 200");
        assertEquals("q3", single.data().path("questionId").asText(), "步骤18 单题统计应返回指定题目");
        assertEquals(120.0, single.data().path("averageNumber").asDouble(), 0.0001,
                "步骤19 单题统计平均值应为 120.0");

        assertEquals(404, call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/not-exist", null, token).status(),
                "步骤20 不存在的题目单题统计应返回 404");
        assertEquals(403, call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats/q1", null, newUserToken()).status(),
                "步骤21 他人查看单题统计应返回 403");
    }

    /* ==================== 用例 7：匿名与多次填写 ==================== */

    @Test
    @Order(7)
    void 用例7_匿名填写与多次填写() {
        String token = newUserToken();
        Map<String, Object> payload = standardSurvey("匿名多次填写问卷");
        payload.put("anonymous", true);
        payload.put("allowMultiple", true);
        String surveyId = createSurvey(token, payload);
        String publicId = publish(token, surveyId);

        String first = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token)
                .data().path("responseId").asText();
        String second = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token)
                .data().path("responseId").asText();
        assertNotNull(first, "步骤1 第一次填写应创建答卷");
        assertNotNull(second, "步骤2 allowMultiple=true 时应能创建第二份答卷");
        assertFalse(first.equals(second), "步骤3 两次填写应是不同的答卷");

        answer(token, first, "q1", "q1_no");
        assertEquals(200, call(HttpMethod.POST, "/api/responses/" + first + "/submit", null, token).status(),
                "步骤4 匿名答卷交卷应返回 200");

        // 匿名问卷不落库 userId，只保存 HMAC 派生标识
        Document doc = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(new org.bson.types.ObjectId(first))),
                Document.class, "responses");
        assertNotNull(doc, "步骤5 答卷应已持久化");
        assertFalse(doc.containsKey("userId"), "步骤6 匿名答卷不应保存 userId 字段");
        assertNotNull(doc.get("respondentKeyHash"), "步骤7 匿名答卷应保存 respondentKeyHash");
    }

    /* ==================== 用例 8：协议层错误码 ==================== */

    @Test
    @Order(8)
    void 用例8_协议层错误码不被误判为500() {
        // 步骤1 未带令牌访问受保护接口，应返回 401 而不是 500
        Api noToken = call(HttpMethod.GET, "/api/surveys/mine", null, null);
        assertEquals(401, noToken.status(), "步骤1 未登录访问应返回 401，实际 " + noToken.status());

        // 步骤2 路径存在但方法不对（/api/surveys 只支持 POST），应返回 405 而不是 500
        Api wrongMethod = call(HttpMethod.GET, "/api/surveys", null, null);
        assertEquals(405, wrongMethod.status(), "步骤2 方法不支持应返回 405，实际 " + wrongMethod.status());
        assertTrue(wrongMethod.message().contains("POST"),
                "步骤2 错误提示应告知正确方法，实际: " + wrongMethod.message());

        // 步骤3 不存在的页面路径应返回 404 而不是 500
        // 注：/api/** 下不存在的路径会先被认证拦截器拦成 401（不泄漏接口是否存在），故此处用页面路径验证 404
        Api notFound = call(HttpMethod.GET, "/no-such-page", null, null);
        assertEquals(404, notFound.status(), "步骤3 未知路径应返回 404，实际 " + notFound.status());

        // 步骤4 Content-Type 不是 application/json，应返回 415 而不是 500
        String token = newUserToken();
        Api wrongMediaType = callRaw(HttpMethod.POST, "/api/surveys", "x", "text/plain", token);
        assertEquals(415, wrongMediaType.status(), "步骤4 媒体类型不符应返回 415，实际 " + wrongMediaType.status());
        assertTrue(wrongMediaType.message().contains("Content-Type"),
                "步骤4 提示应指出 Content-Type，实际: " + wrongMediaType.message());

        // 步骤5 JSON 语法错误，应返回 400 而不是 500
        Api malformed = callRaw(HttpMethod.POST, "/api/surveys", "{bad json", "application/json", token);
        assertEquals(400, malformed.status(), "步骤5 JSON 语法错误应返回 400，实际 " + malformed.status());
    }

    /* ==================== 用例 9：问卷生命周期与截止时间 ==================== */

    @Test
    @Order(9)
    void 用例9_问卷生命周期与截止时间() {
        String token = newUserToken();

        // 删除草稿：删除后不可再查看
        String draftId = createSurvey(token, standardSurvey("待删除草稿"));
        assertEquals(200, call(HttpMethod.DELETE, "/api/surveys/" + draftId, null, token).status(),
                "步骤1 删除草稿应返回 200");
        assertEquals(404, call(HttpMethod.GET, "/api/surveys/" + draftId, null, token).status(),
                "步骤2 已删除的草稿应返回 404");

        // 截止时间：过去时间被拒绝，未来时间被接受
        String surveyId = createSurvey(token, standardSurvey("截止时间测试"));
        Api past = call(HttpMethod.PUT, "/api/surveys/" + surveyId + "/deadline",
                Map.of("deadline", Instant.now().minusSeconds(3600).toString()), token);
        assertEquals(400, past.status(), "步骤3 截止时间早于当前时间应返回 400");

        String future = Instant.now().plusSeconds(86400).toString();
        Api ok = call(HttpMethod.PUT, "/api/surveys/" + surveyId + "/deadline", Map.of("deadline", future), token);
        assertEquals(200, ok.status(), "步骤4 截止时间为未来时间应返回 200");
        assertFalse(ok.data().path("deadline").isNull(), "步骤5 截止时间应已写入");

        // 取消截止时间：传 null 合法
        assertEquals(200, call(HttpMethod.PUT, "/api/surveys/" + surveyId + "/deadline",
                java.util.Collections.singletonMap("deadline", null), token).status(),
                "步骤6 传 null 表示取消截止时间，应返回 200");

        // 草稿未发布时没有 publicId，无法开始答题；发布后关闭则不可填写
        String publicId = publish(token, surveyId);
        assertEquals(200, call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token).status(),
                "步骤7 已发布问卷应可开始答题");

        assertEquals(200, call(HttpMethod.POST, "/api/surveys/" + surveyId + "/close", null, token).status(),
                "步骤8 关闭已发布问卷应返回 200");
        assertEquals(409, call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token).status(),
                "步骤9 已关闭问卷不可开始答题，应返回 409");
        assertEquals(409, call(HttpMethod.POST, "/api/surveys/" + surveyId + "/close", null, token).status(),
                "步骤10 重复关闭应返回 409");
        assertEquals(409, call(HttpMethod.PUT, "/api/surveys/" + surveyId + "/deadline",
                Map.of("deadline", future), token).status(),
                "步骤11 已关闭问卷不可修改截止时间，应返回 409");
        assertEquals(409, call(HttpMethod.POST, "/api/surveys/" + surveyId + "/publish", null, token).status(),
                "步骤12 已关闭问卷不可再次发布，应返回 409");
    }

    /* ==================== 用例 10：登录失败限流 ==================== */

    @Test
    @Order(10)
    void 用例10_登录失败限流() {
        String username = "itest_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsernames.add(username);
        call(HttpMethod.POST, "/api/auth/register", Map.of("username", username, "password", PASSWORD), null);

        // 窗口内允许的失败次数为 10 次，第 11 次进入限流
        for (int attempt = 1; attempt <= 10; attempt++) {
            Api fail = call(HttpMethod.POST, "/api/auth/login",
                    Map.of("username", username, "password", "wrong-password"), null);
            assertEquals(401, fail.status(), "步骤1 第 " + attempt + " 次错误密码应返回 401");
        }

        Api blocked = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", "wrong-password"), null);
        assertEquals(429, blocked.status(), "步骤2 第 11 次失败应返回 429");
        assertTrue(blocked.message().contains("登录失败次数过多"),
                "步骤3 提示应说明失败次数过多，实际: " + blocked.message());

        // 限流期间即使密码正确也被拒绝
        Api correctWhileBlocked = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", PASSWORD), null);
        assertEquals(429, correctWhileBlocked.status(),
                "步骤4 限流期间正确密码同样应返回 429（已知取舍，见 LoginAttemptGuard 注释）");

        // 用户名大小写不同不能绕过计数
        Api upperCase = call(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username.toUpperCase(), "password", "wrong-password"), null);
        assertEquals(429, upperCase.status(), "步骤5 改变用户名大小写不应绕过限流");
    }

    /* ==================== 用例 11：答卷会话归属与恢复 ==================== */

    @Test
    @Order(11)
    void 用例11_答卷会话归属与恢复() {
        String tokenA = newUserToken();
        String tokenB = newUserToken();
        String surveyId = createSurvey(tokenA, standardSurvey("会话归属测试"));
        String publicId = publish(tokenA, surveyId);

        String responseIdA = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, tokenA)
                .data().path("responseId").asText();
        assertFalse(responseIdA.isBlank(), "步骤1 用户 A 应创建答卷会话");

        // 他人不能继续该会话
        assertEquals(403, answer(tokenB, responseIdA, "q1", "q1_yes").status(),
                "步骤2 用户 B 提交 A 的答卷应返回 403");
        assertEquals(403, call(HttpMethod.POST, "/api/responses/" + responseIdA + "/submit", null, tokenB).status(),
                "步骤3 用户 B 交 A 的答卷应返回 403");

        // 本人再次 start 应恢复同一份会话，而不是新建
        Api resumed = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, tokenA);
        assertEquals(200, resumed.status(), "步骤4 本人再次开始应返回 200");
        assertEquals(responseIdA, resumed.data().path("responseId").asText(),
                "步骤5 allowMultiple=false 时应恢复原会话而非新建");
        assertEquals("已恢复答题会话", resumed.data().path("message").asText(), "步骤6 应提示恢复会话");

        // 恢复后当前题目应仍是未作答的第一题
        assertEquals("q1", resumed.data().path("question").path("id").asText(),
                "步骤7 恢复后当前题目应为未作答的 q1");
    }

    /* ==================== 用例 12：统计分母受跳转影响 ==================== */

    @Test
    @Order(12)
    void 用例12_统计分母受跳转影响() {
        String ownerToken = newUserToken();
        String tokenA = newUserToken();
        String tokenB = newUserToken();

        // q1 选 opt_b 直接跳到 END，q2 为必答文本题
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "统计分母测试");
        List<Object> questions = new ArrayList<>();
        questions.add(question("q1", 1, "single", "是否继续？", true,
                List.of(option("opt_a", "继续", 1), option("opt_b", "结束", 2)), null,
                List.of(jump("eq", "opt_b", null, "END"))));
        questions.add(question("q2", 2, "text", "补充说明", true, List.of(), null, List.of()));
        payload.put("questions", questions);

        String surveyId = createSurvey(ownerToken, payload);
        String publicId = publish(ownerToken, surveyId);

        // 填写人 A：不命中跳转，答完两题
        String ridA = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, tokenA)
                .data().path("responseId").asText();
        assertEquals("q2", answer(tokenA, ridA, "q1", "opt_a").data().path("nextQuestion").path("id").asText(),
                "步骤1 A 选「继续」应进入 q2");
        answer(tokenA, ridA, "q2", "A 的说明");
        assertEquals(200, call(HttpMethod.POST, "/api/responses/" + ridA + "/submit", null, tokenA).status(),
                "步骤2 A 交卷应返回 200");

        // 填写人 B：命中跳转，只答一题
        String ridB = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, tokenB)
                .data().path("responseId").asText();
        assertTrue(answer(tokenB, ridB, "q1", "opt_b").data().path("completed").asBoolean(),
                "步骤3 B 选「结束」应直接完成");
        assertEquals(200, call(HttpMethod.POST, "/api/responses/" + ridB + "/submit", null, tokenB).status(),
                "步骤4 B 交卷应返回 200");

        Api stats = call(HttpMethod.GET, "/api/surveys/" + surveyId + "/stats", null, ownerToken);
        assertEquals(200, stats.status(), "步骤5 统计应返回 200");
        assertEquals(2, stats.data().path("submittedCount").asLong(), "步骤6 已提交份数应为 2");

        JsonNode q1 = stats.data().path("questions").get(0);
        JsonNode q2 = stats.data().path("questions").get(1);
        assertEquals(2, q1.path("answeredCount").asLong(), "步骤7 q1 被两人回答，answeredCount 应为 2");
        assertEquals(1, q2.path("answeredCount").asLong(),
                "步骤8 q2 被跳转跳过，answeredCount 应为 1，而非等于提交份数 2");
        assertEquals(1, q1.path("options").get(0).path("count").asLong(), "步骤9 q1「继续」计数应为 1");
        assertEquals(1, q1.path("options").get(1).path("count").asLong(), "步骤10 q1「结束」计数应为 1");
    }

    /* ==================== 数据清理 ==================== */

    @AfterAll
    void cleanUpTestData() {
        if (createdUsernames.isEmpty()) {
            return;
        }
        Query userQuery = Query.query(Criteria.where("username").in(createdUsernames));
        List<String> userIds = mongoTemplate.find(userQuery, Document.class, "users").stream()
                .map(doc -> doc.get("_id").toString())
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Query surveyQuery = Query.query(Criteria.where("ownerId").in(userIds));
        List<String> surveyIds = mongoTemplate.find(surveyQuery, Document.class, "surveys").stream()
                .map(doc -> doc.get("_id").toString())
                .toList();
        if (!surveyIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("surveyId").in(surveyIds)), "responses");
        }
        mongoTemplate.remove(surveyQuery, "surveys");
        mongoTemplate.remove(userQuery, "users");
    }
}
