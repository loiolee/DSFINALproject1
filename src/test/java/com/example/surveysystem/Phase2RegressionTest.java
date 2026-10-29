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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二阶段第二轮测试：兼容性、权限矩阵与前后端契约。
 *
 * <p>与 {@link RequiredAcceptanceTest} 的分工：那个类按课程六类必测项组织，证明「功能做出来了」；
 * 本类换三个角度，证明「功能在边界上不出错」：</p>
 * <ol>
 *   <li>{@link #用例7_历史数据兼容测试()} —— 第一阶段存量数据在新代码下能否正常工作，
 *       迁移脚本 {@code db/migrate-phase2.js} 要修的就是这批数据；</li>
 *   <li>{@link #用例8_权限矩阵测试()} —— 创建者 / 可修改 / 可使用 / 无关用户 × 九种操作的完整矩阵，
 *       逐格断言状态码，防止某个接口漏做校验；</li>
 *   <li>{@link #用例9_版本隔离与换绑测试()} —— 改题不影响已发布问卷、换绑只影响草稿、统计不混版本；</li>
 *   <li>{@link #用例10_引用只读与归档测试()} —— 「引用即只读」与「归档不可再选」两条约束的边界；</li>
 *   <li>{@link #用例11_前端契约测试()} —— 页面依赖的响应字段逐个断言，包括上一轮走查中
 *       真实修掉的四个缺陷（{@code accessLevel}、{@code newerVersionAvailable}、
 *       {@code isInteger} 字段名、归档题目不进选题列表）。</li>
 * </ol>
 *
 * <p>测试方式与 {@code RequiredAcceptanceTest} 一致：真实 Web 容器 + 真实 MongoDB + 真实 HTTP。
 * 历史数据用 {@code MongoTemplate} 直接插入 BSON 文档构造，绕开实体默认值，
 * 这样才能真正复现「第一阶段写下的、缺新字段的文档」，而不是被 {@code @Builder.Default} 悄悄补全的文档。</p>
 *
 * <p>测试账号前缀 {@code reg_}，与第一阶段的 {@code itest_}、验收测试的 {@code acc_} 区分，结束后精确清理。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2RegressionTest {

    private static final String PASSWORD = "password123";
    private static final String USERNAME_PREFIX = "reg_";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsernames = new ArrayList<>();

    /* ==================================================================== 基础设施 */

    private record Api(int status, JsonNode json) {
        String message() {
            return json.path("message").asText("");
        }

        JsonNode data() {
            return json.path("data");
        }
    }

    /** 账号 + 令牌。历史数据用例需要 ownerId，所以要把用户名也留下来查 users 集合。 */
    private record Account(String username, String token) {
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

    private Account newUser() {
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
        return new Account(username, token);
    }

    /** 用户名 → users._id 的十六进制字符串，用于直接插入历史数据时填 ownerId。 */
    private String userIdOf(String username) {
        Document user = mongoTemplate.findOne(
                Query.query(Criteria.where("username").is(username)), Document.class, "users");
        if (user == null) {
            throw new IllegalStateException("找不到测试账号: " + username);
        }
        return user.get("_id").toString();
    }

    private String createSurvey(String token, Map<String, Object> payload) {
        Api api = call(HttpMethod.POST, "/api/surveys", payload, token);
        assertEquals(200, api.status(), "前置条件：创建问卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("id").asText();
    }

    private String publish(String token, String surveyId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + surveyId + "/publish", null, token);
        assertEquals(200, api.status(), "前置条件：发布问卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("publicId").asText();
    }

    private String startResponse(String token, String publicId) {
        Api api = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, token);
        assertEquals(200, api.status(), "前置条件：开始答卷应成功，实际 " + api.status() + " " + api.message());
        return api.data().path("responseId").asText();
    }

    private Api answer(String token, String responseId, String questionId, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questionId", questionId);
        body.put("answer", value);
        return call(HttpMethod.POST, "/api/responses/" + responseId + "/answer", body, token);
    }

    private Api submit(String token, String responseId) {
        return call(HttpMethod.POST, "/api/responses/" + responseId + "/submit", null, token);
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

    private Map<String, Object> singleQuestionSurvey(String title) {
        Map<String, Object> survey = new LinkedHashMap<>();
        survey.put("title", title);
        survey.put("description", "回归测试问卷");
        survey.put("allowMultiple", true);
        survey.put("questions", List.of(question("q1", 1, "single", "请选择一个选项", true,
                List.of(option("o1", "选项一", 1), option("o2", "选项二", 2)), null, List.of())));
        return survey;
    }

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
        body.put("changeNote", "回归测试创建");
        return body;
    }

    private String[] createLibraryQuestion(String token, String title, String type,
                                           List<? extends Map<String, ?>> options,
                                           Map<String, ?> constraints) {
        Api api = call(HttpMethod.POST, "/api/questions",
                libraryQuestionPayload(title, type, options, constraints), token);
        assertEquals(200, api.status(), "前置条件：创建题库题目应成功，实际 " + api.status() + " " + api.message());
        return new String[]{api.data().path("id").asText(), api.data().path("currentVersion").path("id").asText()};
    }

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

    private Map<String, Object> surveyWithRef(String title, Map<String, Object> ref) {
        Map<String, Object> survey = new LinkedHashMap<>();
        survey.put("title", title);
        survey.put("description", "引用题库题目");
        survey.put("questions", List.of(ref));
        return survey;
    }

    /** 从「我的问卷」列表里按 ID 取一份问卷的 JSON，找不到返回 null。 */
    private JsonNode findInMine(String token, String surveyId) {
        Api mine = call(HttpMethod.GET, "/api/surveys/mine", null, token);
        assertEquals(200, mine.status(), "前置条件：读取我的问卷应成功");
        for (JsonNode node : mine.data()) {
            if (surveyId.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 直接插入一份「第一阶段形状」的问卷文档：题目只有第一阶段那五个字段
     * （id / order / type / title / required / options / jumps），没有 sourceType 等新字段。
     *
     * <p>不能用 API 创建：走 API 会被实体默认值补成 {@code sourceType="local"}，
     * 那就测不到「字段真的不存在」这种情况了。</p>
     */
    private String insertLegacySurvey(String ownerId, String title, String status, boolean allowMultiple) {
        Document legacyQuestion = new Document("_id", "q1")
                .append("order", 1)
                .append("type", "single")
                .append("title", "第一阶段的历史单选题")
                .append("required", true)
                .append("options", List.of(
                        new Document("_id", "o1").append("label", "历史选项一").append("order", 1),
                        new Document("_id", "o2").append("label", "历史选项二").append("order", 2)))
                .append("jumps", List.of());

        Document survey = new Document("ownerId", ownerId)
                .append("title", title)
                .append("description", "由测试直接写入，模拟第一阶段文档")
                .append("anonymous", false)
                .append("allowMultiple", allowMultiple)
                .append("status", status)
                .append("createdAt", new Date())
                .append("updatedAt", new Date())
                .append("questions", List.of(legacyQuestion));
        if ("published".equals(status)) {
            survey.append("publicId", "LG" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        }
        mongoTemplate.getCollection("surveys").insertOne(survey);
        return survey.getObjectId("_id").toHexString();
    }

    /** 直接插入一份「第一阶段形状」的答卷：answers 里没有 libraryQuestionId / questionVersionId。 */
    private void insertLegacyResponse(String surveyId, String userId, String optionId) {
        Document answer = new Document("questionId", "q1")
                .append("type", "single")
                .append("optionIds", List.of(optionId))
                .append("answeredAt", new Date());
        Document response = new Document("surveyId", surveyId)
                .append("userId", userId)
                .append("anonymous", false)
                .append("status", "submitted")
                .append("startedAt", new Date())
                .append("submittedAt", new Date())
                .append("updatedAt", new Date())
                .append("visitedQuestionIds", List.of("q1"))
                .append("answers", List.of(answer));
        mongoTemplate.getCollection("responses").insertOne(response);
    }

    /* ======================================================== 用例 7：历史数据兼容 */

    /**
     * 第一阶段存量数据的兼容性。
     *
     * <p>核心断言：缺少 {@code sourceType} 等新字段的文档，在第二阶段代码下必须被当作<b>本地题目</b>
     * 正常读写，而不是报错或被误判成题库引用。这正是 {@code db/migrate-phase2.js} 要消除的状态——
     * 迁移不是为了让旧数据「能跑」（本来就能跑），而是为了让字段语义确定、校验器可用。</p>
     */
    @Test
    @Order(7)
    void 用例7_历史数据兼容测试() {
        Account owner = newUser();
        Account respondent = newUser();
        String ownerId = userIdOf(owner.username());

        // 前置：直接写入一份「第一阶段已发布问卷」，题目不含 sourceType / questionId / questionVersionId
        String legacySurveyId = insertLegacySurvey(ownerId, "第一阶段历史问卷", "published", true);
        Document raw = mongoTemplate.getCollection("surveys")
                .find(new Document("_id", new ObjectId(legacySurveyId))).first();
        Document rawQuestion = (Document) ((List<?>) raw.get("questions")).get(0);
        assertFalse(rawQuestion.containsKey("sourceType"), "前置：历史文档确实没有 sourceType 字段");

        // 步骤1~5：新代码读取历史问卷不报错
        Api detail = call(HttpMethod.GET, "/api/surveys/" + legacySurveyId, null, owner.token());
        assertEquals(200, detail.status(), "步骤1 读取历史问卷应返回 200，实际 " + detail.status() + " " + detail.message());
        assertEquals("第一阶段历史问卷", detail.data().path("title").asText(), "步骤2 标题应原样返回");
        JsonNode legacyQ = detail.data().path("questions").get(0);
        assertEquals("第一阶段的历史单选题", legacyQ.path("title").asText(), "步骤3 题干应原样返回");
        assertEquals(2, legacyQ.path("options").size(), "步骤4 选项应原样返回");
        // 步骤5：缺少 sourceType 时，实体字段的初始值把它补成 local —— 旧数据在 API 层已经按本地题目呈现。
        // 这正是「不迁移也能跑」的原因，也说明迁移脚本的价值不在运行期，而在库内字段确定、校验器可用。
        assertEquals("local", legacyQ.path("sourceType").asText(),
                "步骤5 缺少 sourceType 的旧数据应按本地题目呈现，实际 " + legacyQ.path("sourceType"));

        // 步骤6~9：历史问卷可以正常答卷（答案引擎不能因为缺 sourceType 而崩溃）
        String publicId = raw.getString("publicId");
        String responseId = startResponse(respondent.token(), publicId);
        assertEquals(200, answer(respondent.token(), responseId, "q1", "o1").status(),
                "步骤6 历史问卷应能提交答案");
        assertEquals(200, submit(respondent.token(), responseId).status(), "步骤7 历史问卷应能交卷");

        Document savedAnswer = mongoTemplate.getCollection("responses")
                .find(new Document("surveyId", legacySurveyId)).first();
        assertTrue(savedAnswer != null, "前置：历史问卷的答卷应已落库");
        Document answerDoc = (Document) ((List<?>) savedAnswer.get("answers")).get(0);
        assertFalse(answerDoc.containsKey("libraryQuestionId"),
                "步骤8 新写入的本地题目答案不应凭空带上 libraryQuestionId");
        assertFalse(answerDoc.containsKey("questionVersionId"),
                "步骤9 新写入的本地题目答案不应凭空带上 questionVersionId");

        // 步骤10~11：统计照常工作，历史答卷被计入
        Api stats = call(HttpMethod.GET, "/api/surveys/" + legacySurveyId + "/stats", null, owner.token());
        assertEquals(200, stats.status(), "步骤10 历史问卷统计应返回 200，实际 " + stats.status() + " " + stats.message());
        assertEquals(1, stats.data().path("questions").get(0).path("answeredCount").asLong(),
                "步骤11 历史答卷应被统计到");

        // 步骤12~14：历史答卷不会被算进任何题库题目的跨问卷统计
        String[] library = createLibraryQuestion(owner.token(), "与历史数据无关的题库题目", "single",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null);
        Api crossStats = call(HttpMethod.GET, "/api/questions/" + library[0] + "/stats", null, owner.token());
        assertEquals(200, crossStats.status(), "步骤12 跨问卷统计应返回 200");
        assertEquals(0, crossStats.data().path("totalAnswered").asLong(),
                "步骤13 没有 libraryQuestionId 的历史答案不应被归到题库题目上");
        assertEquals(0, crossStats.data().path("surveyCount").asInt(),
                "步骤14 历史问卷不计入该题库题目的引用数");

        // 步骤15~17：历史草稿第一次保存后，字段被补齐为 local（迁移脚本做的事，应用侧也会顺带做）
        String legacyDraftId = insertLegacySurvey(ownerId, "第一阶段历史草稿", "draft", false);
        Api saved = call(HttpMethod.PUT, "/api/surveys/" + legacyDraftId, singleQuestionSurvey("第一阶段历史草稿"), owner.token());
        assertEquals(200, saved.status(), "步骤15 保存历史草稿应返回 200，实际 " + saved.status() + " " + saved.message());
        assertEquals("local", saved.data().path("questions").get(0).path("sourceType").asText(),
                "步骤16 保存后新题目应显式标记为 local");
        Document afterSave = mongoTemplate.getCollection("surveys")
                .find(new Document("_id", new ObjectId(legacyDraftId))).first();
        Document afterQuestion = (Document) ((List<?>) afterSave.get("questions")).get(0);
        assertTrue(afterQuestion.containsKey("sourceType"), "步骤17 保存后数据库中应落库 sourceType");
    }

    /* ======================================================== 用例 8：权限矩阵 */

    /**
     * 四种角色 × 九种操作的权限矩阵。
     *
     * <p>每个格子都断言状态码，目的不是复述代码，而是防止「某个接口漏做校验」——
     * 这类漏洞在只测正常路径时完全看不出来，而且一旦漏做，被授权者就能替创建者归档题目、
     * 或查看别人问卷的引用情况。</p>
     */
    @Test
    @Order(8)
    void 用例8_权限矩阵测试() {
        Account owner = newUser();
        Account editor = newUser();
        Account user = newUser();
        Account outsider = newUser();

        // 前置：创建者建题
        String[] library = createLibraryQuestion(owner.token(), "权限矩阵题目", "single",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null);
        String questionId = library[0];
        String versionId = library[1];

        // 步骤1~2：分别授予 edit 与 use
        assertEquals(200, call(HttpMethod.POST, "/api/questions/" + questionId + "/shares",
                Map.of("username", editor.username(), "permission", "edit"), owner.token()).status(),
                "步骤1 授予 edit 权限应返回 200");
        assertEquals(200, call(HttpMethod.POST, "/api/questions/" + questionId + "/shares",
                Map.of("username", user.username(), "permission", "use"), owner.token()).status(),
                "步骤2 授予 use 权限应返回 200");

        // 步骤3~7：读取详情 —— 三种角色都能读，accessLevel 各不相同，无关用户被拒
        Api ownerDetail = call(HttpMethod.GET, "/api/questions/" + questionId, null, owner.token());
        Api editorDetail = call(HttpMethod.GET, "/api/questions/" + questionId, null, editor.token());
        Api userDetail = call(HttpMethod.GET, "/api/questions/" + questionId, null, user.token());
        Api outsiderDetail = call(HttpMethod.GET, "/api/questions/" + questionId, null, outsider.token());
        assertEquals(200, ownerDetail.status(), "步骤3 创建者应能读详情");
        assertEquals("owner", ownerDetail.data().path("accessLevel").asText(), "步骤4 创建者的 accessLevel 应为 owner");
        assertEquals("edit", editorDetail.data().path("accessLevel").asText(), "步骤5 edit 被授权者的 accessLevel 应为 edit");
        assertEquals("use", userDetail.data().path("accessLevel").asText(), "步骤6 use 被授权者的 accessLevel 应为 use");
        assertEquals(403, outsiderDetail.status(), "步骤7 无关用户读详情应返回 403，实际 " + outsiderDetail.status());

        // 步骤8~11：修改内容 —— 只有创建者与 edit 可以
        Api ownerUpdate = call(HttpMethod.PUT, "/api/questions/" + questionId,
                libraryQuestionPayload("权限矩阵题目", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null), owner.token());
        assertEquals(200, ownerUpdate.status(), "步骤8 创建者修改应返回 200");
        assertEquals(2, ownerUpdate.data().path("currentVersionNo").asInt(), "步骤9 修改后版本号应为 2");
        Api editorUpdate = call(HttpMethod.PUT, "/api/questions/" + questionId,
                libraryQuestionPayload("权限矩阵题目（edit 改）", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null), editor.token());
        assertEquals(200, editorUpdate.status(), "步骤10 edit 被授权者修改应返回 200");
        Api userUpdate = call(HttpMethod.PUT, "/api/questions/" + questionId,
                libraryQuestionPayload("权限矩阵题目（use 改）", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null), user.token());
        assertEquals(403, userUpdate.status(), "步骤11 use 被授权者修改应返回 403，实际 " + userUpdate.status());

        // 步骤12~13：恢复旧版本 —— 同「修改」一档
        assertEquals(200, call(HttpMethod.POST, "/api/questions/" + questionId + "/versions/1/restore", null, editor.token()).status(),
                "步骤12 edit 被授权者恢复旧版本应返回 200");
        assertEquals(403, call(HttpMethod.POST, "/api/questions/" + questionId + "/versions/1/restore", null, user.token()).status(),
                "步骤13 use 被授权者恢复旧版本应返回 403");

        // 步骤14~17：创建者独有的三项 —— 归档、授权、查看使用情况与统计
        assertEquals(403, call(HttpMethod.GET, "/api/questions/" + questionId + "/usages", null, editor.token()).status(),
                "步骤14 edit 被授权者查看使用情况应返回 403");
        assertEquals(403, call(HttpMethod.GET, "/api/questions/" + questionId + "/stats", null, editor.token()).status(),
                "步骤15 edit 被授权者查看跨问卷统计应返回 403");
        assertEquals(403, call(HttpMethod.GET, "/api/questions/" + questionId + "/shares", null, user.token()).status(),
                "步骤16 use 被授权者查看授权列表应返回 403");
        assertEquals(403, call(HttpMethod.POST, "/api/questions/" + questionId + "/shares",
                Map.of("username", outsider.username(), "permission", "use"), editor.token()).status(),
                "步骤17 edit 被授权者转授给第三人应返回 403（不允许二级转授）");

        // 步骤18~20：被授权者可以把自己看到的题目选进自己的问卷
        String editorSurvey = createSurvey(editor.token(),
                surveyWithRef("edit 者的问卷", libraryRef("q1", 1, questionId, versionId, 1)));
        assertEquals(200, call(HttpMethod.GET, "/api/surveys/" + editorSurvey, null, editor.token()).status(),
                "步骤18 edit 被授权者引用题目后应能读自己的问卷");
        String userSurvey = createSurvey(user.token(),
                surveyWithRef("use 者的问卷", libraryRef("q1", 1, questionId, versionId, 1)));
        assertEquals(200, call(HttpMethod.GET, "/api/surveys/" + userSurvey, null, user.token()).status(),
                "步骤19 use 被授权者引用题目后应能读自己的问卷");
        Api outsiderRef = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("无关用户的问卷", libraryRef("q1", 1, questionId, versionId, 1)), outsider.token());
        assertEquals(403, outsiderRef.status(), "步骤20 无关用户引用题目应返回 403，实际 " + outsiderRef.status());

        // 步骤21~23：归档只有创建者能做
        assertEquals(403, call(HttpMethod.DELETE, "/api/questions/" + questionId, null, editor.token()).status(),
                "步骤21 edit 被授权者归档应返回 403");
        assertEquals(403, call(HttpMethod.DELETE, "/api/questions/" + questionId, null, user.token()).status(),
                "步骤22 use 被授权者归档应返回 403");
        assertEquals(200, call(HttpMethod.DELETE, "/api/questions/" + questionId, null, owner.token()).status(),
                "步骤23 创建者归档应返回 200");

        // 步骤24~27：撤销授权后立刻失去读权限，但对方已有问卷里的快照不受影响
        assertEquals(200, call(HttpMethod.DELETE, "/api/questions/" + questionId + "/shares/" + userIdOf(user.username()),
                null, owner.token()).status(), "步骤24 创建者撤销 use 授权应返回 200");
        assertEquals(403, call(HttpMethod.GET, "/api/questions/" + questionId, null, user.token()).status(),
                "步骤25 撤销后应立刻失去读权限，实际非 403");
        Api userSurveyAfterRevoke = call(HttpMethod.GET, "/api/surveys/" + userSurvey, null, user.token());
        assertEquals(200, userSurveyAfterRevoke.status(), "步骤26 撤销授权不应影响对方已有问卷");
        assertEquals("权限矩阵题目", userSurveyAfterRevoke.data().path("questions").get(0).path("title").asText(),
                "步骤27 对方问卷里的题目快照应保持原样");
    }

    /* ================================================ 用例 9：版本隔离与换绑 */

    /**
     * 「改题不影响已做好的问卷」这条需求的边界。
     *
     * <p>要同时验证四件事：已发布问卷绑定的版本不随题库变化；换绑只对草稿开放；
     * 换绑后统计仍按版本分组；换绑不会改动历史答卷。</p>
     */
    @Test
    @Order(9)
    void 用例9_版本隔离与换绑测试() {
        Account owner = newUser();
        Account first = newUser();
        Account second = newUser();

        // 前置：题库题目 v1，两个选项
        String[] library = createLibraryQuestion(owner.token(), "版本隔离题目", "single",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null);
        String questionId = library[0];
        String v1Id = library[1];

        // 前置：已发布问卷绑定 v1 并发布
        String publishedSurvey = createSurvey(owner.token(),
                surveyWithRef("已发布问卷", libraryRef("q1", 1, questionId, v1Id, 1)));
        String publicId = publish(owner.token(), publishedSurvey);

        // 步骤1~2：收第一份答卷
        String firstResponse = startResponse(first.token(), publicId);
        assertEquals(200, answer(first.token(), firstResponse, "q1", "c1").status(), "步骤1 第一份答卷应能提交答案");
        assertEquals(200, submit(first.token(), firstResponse).status(), "步骤2 第一份答卷应能交卷");

        // 前置：草稿问卷同样绑定 v1
        String draftSurvey = createSurvey(owner.token(),
                surveyWithRef("草稿问卷", libraryRef("q1", 1, questionId, v1Id, 1)));

        // 步骤3~4：题库改成 v2（三个选项）
        Api update = call(HttpMethod.PUT, "/api/questions/" + questionId,
                libraryQuestionPayload("版本隔离题目", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2), option("c3", "选项三", 3)), null),
                owner.token());
        assertEquals(200, update.status(), "步骤3 更新题目应返回 200");
        assertEquals(3, update.data().path("currentVersion").path("options").size(), "步骤4 v2 应有 3 个选项");

        // 步骤5~7：已发布问卷仍看到 v1 的 2 个选项
        Api publishedDetail = call(HttpMethod.GET, "/api/surveys/" + publishedSurvey, null, owner.token());
        JsonNode publishedQuestion = publishedDetail.data().path("questions").get(0);
        assertEquals(2, publishedQuestion.path("options").size(), "步骤5 已发布问卷的选项数不应随题库变化");
        assertEquals(v1Id, publishedQuestion.path("questionVersionId").asText(), "步骤6 已发布问卷仍绑定 v1");
        assertEquals(1, publishedQuestion.path("versionNo").asInt(), "步骤7 已发布问卷的版本号仍为 1");

        // 步骤8~11：第二个人填写；同一人再次开始会被「恢复未完成答卷」接住，返回同一个 responseId
        String secondResponse = startResponse(second.token(), publicId);
        Api resumed = call(HttpMethod.POST, "/api/surveys/" + publicId + "/start", null, second.token());
        assertEquals(200, resumed.status(), "步骤8 未完成答卷再次开始应返回 200（恢复会话），实际 " + resumed.status());
        assertEquals(secondResponse, resumed.data().path("responseId").asText(),
                "步骤9 恢复会话应返回同一个 responseId，而不是新建一份");
        assertEquals(200, answer(second.token(), secondResponse, "q1", "c2").status(), "步骤10 第二份答卷应能提交答案");
        assertEquals(200, submit(second.token(), secondResponse).status(), "步骤11 第二份答卷应能交卷");

        // 步骤12~13：草稿能看到「有新版本」提示
        Api draftDetail = call(HttpMethod.GET, "/api/surveys/" + draftSurvey, null, owner.token());
        JsonNode draftQuestion = draftDetail.data().path("questions").get(0);
        assertTrue(draftQuestion.path("newerVersionAvailable").asBoolean(), "步骤12 草稿应提示题库有新版本");
        assertEquals(2, draftQuestion.path("latestVersionNo").asInt(), "步骤13 应回填题库最新版本号 2");

        // 步骤14~15：草稿换绑到 v2
        Api rebind = call(HttpMethod.PUT, "/api/surveys/" + draftSurvey + "/questions/q1/version?versionNo=2",
                null, owner.token());
        assertEquals(200, rebind.status(), "步骤14 草稿换绑到 v2 应返回 200，实际 " + rebind.status() + " " + rebind.message());
        assertEquals(3, rebind.data().path("questions").get(0).path("options").size(), "步骤15 换绑后选项应来自 v2");

        // 步骤16~17：已发布问卷不允许换绑
        Api publishedRebind = call(HttpMethod.PUT, "/api/surveys/" + publishedSurvey + "/questions/q1/version?versionNo=2",
                null, owner.token());
        assertEquals(409, publishedRebind.status(), "步骤16 已发布问卷换绑应返回 409，实际 " + publishedRebind.status());
        assertTrue(publishedRebind.message().contains("只有草稿"), "步骤17 应提示只有草稿可以换绑，实际: " + publishedRebind.message());

        // 步骤18：换绑到不存在的版本
        Api ghostRebind = call(HttpMethod.PUT, "/api/surveys/" + draftSurvey + "/questions/q1/version?versionNo=99",
                null, owner.token());
        assertTrue(ghostRebind.status() >= 400, "步骤18 换绑到不存在的版本应失败，实际 " + ghostRebind.status());

        // 步骤19~20：换绑不影响历史答卷，也不改已发布问卷的统计口径
        Api publishedStats = call(HttpMethod.GET, "/api/surveys/" + publishedSurvey + "/stats", null, owner.token());
        assertEquals(2, publishedStats.data().path("questions").get(0).path("answeredCount").asLong(),
                "步骤19 已发布问卷的统计仍应覆盖两份历史答卷");
        assertEquals(2, publishedStats.data().path("questions").get(0).path("options").size(),
                "步骤20 统计的选项集合应来自该问卷绑定的 v1，不应出现 v2 的第 3 个选项");

        // 步骤21~27：跨问卷统计按版本分组，v1 与 v2 各算各的
        Api crossStats = call(HttpMethod.GET, "/api/questions/" + questionId + "/stats", null, owner.token());
        assertEquals(200, crossStats.status(), "步骤21 跨问卷统计应返回 200");
        // 分组顺序按版本号倒序（最新版本在前），与版本历史页一致；断言顺序本身也是契约的一部分
        JsonNode versions = crossStats.data().path("versions");
        assertEquals(2, versions.size(), "步骤22 应分成 v1 与 v2 两组");
        assertEquals(2, versions.get(0).path("versionNo").asInt(), "步骤23 第一组应是最新的 v2");
        assertEquals(0, versions.get(0).path("answeredCount").asLong(), "步骤24 v2 还没有回答");
        assertEquals(1, versions.get(1).path("versionNo").asInt(), "步骤25 第二组应是 v1");
        assertEquals(2, versions.get(1).path("answeredCount").asLong(), "步骤26 v1 应有 2 条回答");
        assertTrue(crossStats.data().path("hasEmptyVersion").asBoolean(),
                "步骤27 有版本无回答时应置 hasEmptyVersion=true，前端据此提示");
    }

    /* ============================================ 用例 10：引用只读与归档 */

    /**
     * 「引用即只读」与「归档不可再选」的边界。
     *
     * <p>这两条约束最容易写成「一刀切」：要么完全不校验内容字段（引用可以被悄悄改掉），
     * 要么校验过头（题目一归档，引用它的旧草稿连保存都做不了）。用例同时验证两侧。</p>
     */
    @Test
    @Order(10)
    void 用例10_引用只读与归档测试() {
        Account owner = newUser();
        Account other = newUser();

        String[] library = createLibraryQuestion(owner.token(), "只读约束题目", "single",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null);
        String questionId = library[0];
        String v1Id = library[1];

        // 步骤1：不带内容字段的纯引用，正常
        Map<String, Object> ref = libraryRef("q1", 1, questionId, v1Id, 1);
        Api okRef = call(HttpMethod.POST, "/api/surveys", surveyWithRef("纯引用问卷", ref), owner.token());
        assertEquals(200, okRef.status(), "步骤1 只传引用字段应成功，实际 " + okRef.status() + " " + okRef.message());

        // 步骤2：内容字段与版本快照完全一致 —— 允许（幂等重发同一份请求不应报错）
        Map<String, Object> sameContent = new LinkedHashMap<>(ref);
        sameContent.put("type", "single");
        sameContent.put("title", "只读约束题目");
        sameContent.put("options", List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)));
        Api sameRef = call(HttpMethod.POST, "/api/surveys", surveyWithRef("内容一致的引用问卷", sameContent), owner.token());
        assertEquals(200, sameRef.status(), "步骤2 内容字段与快照一致时应成功，实际 " + sameRef.status() + " " + sameRef.message());

        // 步骤3：篡改题干
        Map<String, Object> tamperedTitle = new LinkedHashMap<>(ref);
        tamperedTitle.put("title", "偷偷改掉的题干");
        Api tamperedTitleApi = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("篡改题干的问卷", tamperedTitle), owner.token());
        assertEquals(400, tamperedTitleApi.status(), "步骤3 篡改题干应返回 400，实际 " + tamperedTitleApi.status());
        assertTrue(tamperedTitleApi.message().contains("题库"), "步骤4 提示应引导去题库创建新版本，实际: " + tamperedTitleApi.message());

        // 步骤5：篡改选项文案
        Map<String, Object> tamperedOption = new LinkedHashMap<>(ref);
        tamperedOption.put("options", List.of(option("c1", "被改掉的选项", 1), option("c2", "选项二", 2)));
        Api tamperedOptionApi = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("篡改选项的问卷", tamperedOption), owner.token());
        assertEquals(400, tamperedOptionApi.status(), "步骤5 篡改选项文案应返回 400，实际 " + tamperedOptionApi.status());

        // 步骤6：引用不存在的版本
        Api ghostVersion = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("引用不存在版本", libraryRef("q1", 1, questionId, "not-a-version", 1)), owner.token());
        assertEquals(400, ghostVersion.status(), "步骤6 引用不存在的版本应返回 400，实际 " + ghostVersion.status());

        // 步骤7：他人未授权引用
        Api otherRef = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("他人引用", ref), other.token());
        assertEquals(403, otherRef.status(), "步骤7 未授权用户引用应返回 403，实际 " + otherRef.status());

        // 步骤8~9：引用时可以只调整「必答性」与「跳转规则」这两项实例配置
        Map<String, Object> instanceConfig = new LinkedHashMap<>(ref);
        instanceConfig.put("required", false);
        Api configRef = call(HttpMethod.POST, "/api/surveys", surveyWithRef("调整实例配置", instanceConfig), owner.token());
        assertEquals(200, configRef.status(), "步骤8 调整必答性应成功，实际 " + configRef.status() + " " + configRef.message());
        assertFalse(configRef.data().path("questions").get(0).path("required").asBoolean(),
                "步骤9 必答性应取问卷内的实例配置");

        // 步骤10~12：归档后不能再选入新问卷
        assertEquals(200, call(HttpMethod.DELETE, "/api/questions/" + questionId, null, owner.token()).status(),
                "步骤10 创建者归档应返回 200");
        Api archivedRef = call(HttpMethod.POST, "/api/surveys",
                surveyWithRef("归档后引用", ref), owner.token());
        assertEquals(400, archivedRef.status(), "步骤11 引用已归档题目应返回 400，实际 " + archivedRef.status());
        assertTrue(archivedRef.message().contains("已归档"), "步骤12 应提示题目已归档，实际: " + archivedRef.message());

        // 步骤13~14：但原本就引用了它的草稿仍能保存（否则归档一次就让旧草稿报废）
        String existingDraft = okRef.data().path("id").asText();
        Api resave = call(HttpMethod.PUT, "/api/surveys/" + existingDraft, surveyWithRef("纯引用问卷", ref), owner.token());
        assertEquals(200, resave.status(), "步骤13 已引用该题的草稿应仍可保存，实际 " + resave.status() + " " + resave.message());
        assertEquals(1, resave.data().path("questions").size(), "步骤14 保存后题目数量应不变");

        // 步骤15~16：归档后不再出现在默认列表，但归档列表能查到
        Api activeList = call(HttpMethod.GET, "/api/questions", null, owner.token());
        boolean inActive = false;
        for (JsonNode node : activeList.data()) {
            if (questionId.equals(node.path("id").asText())) {
                inActive = true;
            }
        }
        assertFalse(inActive, "步骤15 已归档题目不应出现在「进行中」列表");
        Api archivedList = call(HttpMethod.GET, "/api/questions?status=archived", null, owner.token());
        boolean inArchived = false;
        for (JsonNode node : archivedList.data()) {
            if (questionId.equals(node.path("id").asText())) {
                inArchived = true;
            }
        }
        assertTrue(inArchived, "步骤16 已归档题目应出现在「已归档」列表");

        // 步骤17：归档题目不能再修改
        Api archivedUpdate = call(HttpMethod.PUT, "/api/questions/" + questionId,
                libraryQuestionPayload("归档后修改", "single",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), null), owner.token());
        assertEquals(409, archivedUpdate.status(), "步骤17 修改已归档题目应返回 409，实际 " + archivedUpdate.status());
    }

    /* ==================================================== 用例 11：前端契约 */

    /**
     * 页面依赖的响应字段逐个断言。
     *
     * <p>这些字段不影响任何后端业务判断，缺了或改名了接口测试照样通过，
     * 但页面会静默失效——上一轮走查发现的四个缺陷全部属于这一类，因此单独固化成一个用例。</p>
     */
    @Test
    @Order(11)
    void 用例11_前端契约测试() {
        Account owner = newUser();
        Account friend = newUser();

        // 步骤1：数字题带 isInteger 约束
        String[] numberQuestion = createLibraryQuestion(owner.token(), "数字契约题目", "number",
                List.of(), constraints("minValue", 0, "maxValue", 100, "isInteger", true));

        // 步骤2~3：isInteger 必须以原名出现在响应里（JavaBean 命名陷阱的回归点）
        Api detail = call(HttpMethod.GET, "/api/questions/" + numberQuestion[0], null, owner.token());
        assertEquals(200, detail.status(), "步骤1 读取数字题详情应返回 200");
        JsonNode numberConstraints = detail.data().path("currentVersion").path("constraints");
        assertTrue(numberConstraints.path("isInteger").asBoolean(),
                "步骤2 constraints.isInteger 应为 true，实际 " + numberConstraints);
        assertTrue(numberConstraints.path("integer").isMissingNode(),
                "步骤3 不应出现被 JavaBean 规范改名后的 integer 字段");

        // 步骤4~6：单选题带 minSelect/maxSelect，问卷内引用后字段名保持不变
        String[] choiceQuestion = createLibraryQuestion(owner.token(), "多选契约题目", "multiple",
                List.of(option("c1", "选项一", 1), option("c2", "选项二", 2)), constraints("minSelect", 1, "maxSelect", 2));
        String surveyId = createSurvey(owner.token(),
                surveyWithRef("契约问卷", libraryRef("q1", 1, choiceQuestion[0], choiceQuestion[1], 1)));
        Api survey = call(HttpMethod.GET, "/api/surveys/" + surveyId, null, owner.token());
        JsonNode surveyQuestion = survey.data().path("questions").get(0);
        assertEquals("library", surveyQuestion.path("sourceType").asText(), "步骤4 引用题目应标记 sourceType=library");
        assertEquals(choiceQuestion[0], surveyQuestion.path("questionId").asText(), "步骤5 应回传题库题目 ID");
        assertEquals(choiceQuestion[1], surveyQuestion.path("questionVersionId").asText(), "步骤6 应回传绑定的版本 ID");
        assertEquals(1, surveyQuestion.path("versionNo").asInt(), "步骤7 应回传版本号");
        assertEquals(1, surveyQuestion.path("constraints").path("minSelect").asInt(), "步骤8 约束应随快照返回");

        // 步骤9~11：题库出新版本后，我的问卷列表必须回填「有新版本」提示
        assertEquals(200, call(HttpMethod.PUT, "/api/questions/" + choiceQuestion[0],
                libraryQuestionPayload("多选契约题目", "multiple",
                        List.of(option("c1", "选项一", 1), option("c2", "选项二", 2), option("c3", "选项三", 3)),
                        constraints("minSelect", 1, "maxSelect", 2)), owner.token()).status(),
                "步骤9 更新题目生成 v2 应成功");
        JsonNode inMine = findInMine(owner.token(), surveyId);
        assertTrue(inMine != null, "步骤10 新建的问卷应出现在我的问卷列表");
        JsonNode mineQuestion = inMine.path("questions").get(0);
        assertTrue(mineQuestion.path("newerVersionAvailable").asBoolean(),
                "步骤11 列表页必须回填 newerVersionAvailable，否则用户看不到「有新版本」");
        assertEquals(2, mineQuestion.path("latestVersionNo").asInt(), "步骤12 列表页应回填 latestVersionNo");

        // 步骤13：没有引用题库的问卷，这两个字段为空且不影响列表
        String localSurvey = createSurvey(owner.token(), singleQuestionSurvey("纯本地问卷"));
        JsonNode localInMine = findInMine(owner.token(), localSurvey);
        assertTrue(localInMine != null, "步骤13 纯本地问卷也应出现在列表");
        assertTrue(localInMine.path("questions").get(0).path("newerVersionAvailable").isNull()
                        || localInMine.path("questions").get(0).path("newerVersionAvailable").isMissingNode(),
                "步骤14 本地题目不应有 newerVersionAvailable 提示");

        // 步骤15~16：选题面板用「status 留空」拉进行中的题目
        Api picker = call(HttpMethod.GET, "/api/questions?status=", null, owner.token());
        assertEquals(200, picker.status(), "步骤15 status 留空应返回 200（等价于只看进行中），实际 " + picker.status());
        assertTrue(picker.data().size() >= 2, "步骤16 status 留空应能取到进行中的题目");

        // 步骤17~18：被授权者的 accessLevel 决定页面显示哪些按钮
        assertEquals(200, call(HttpMethod.POST, "/api/questions/" + choiceQuestion[0] + "/shares",
                Map.of("username", friend.username(), "permission", "use"), owner.token()).status(),
                "步骤17 共享给好友应返回 200");
        Api friendDetail = call(HttpMethod.GET, "/api/questions/" + choiceQuestion[0], null, friend.token());
        assertEquals("use", friendDetail.data().path("accessLevel").asText(),
                "步骤18 被授权者拿到的 accessLevel 应为 use，页面据此隐藏修改与归档按钮");

        // 步骤19~20：共享给我的列表里带 questionStatus，页面用它过滤已归档的题目
        Api sharedWithMe = call(HttpMethod.GET, "/api/questions/shared-with-me", null, friend.token());
        assertEquals(200, sharedWithMe.status(), "步骤19 共享给我的列表应返回 200");
        assertEquals(1, sharedWithMe.data().size(), "步骤20 应返回 1 条共享记录");
        JsonNode share = sharedWithMe.data().get(0);
        assertEquals("active", share.path("questionStatus").asText(), "步骤21 共享记录应带 questionStatus 供页面过滤");
        assertEquals("use", share.path("permission").asText(), "步骤22 共享记录应带 permission 供页面显示权限徽标");
        assertEquals(owner.username(), share.path("ownerUsername").asText(), "步骤23 共享记录应带授权者用户名");
    }

    /* ================================================ 用例 12：本地题目存入题库 */

    /**
     * 「把问卷里的题目存入题库」（需求「可以把题加入题库」）。
     *
     * <p>三个必须守住的语义：① 导入后<b>原问卷不变</b>，题目仍是本地题目，不会被悄悄换绑成引用；
     * ② 同一份问卷的同一道题只能沉淀一次，重复点击返回 409，而不是造出两道同源题目；
     * ③ 只有问卷创建者能导入。</p>
     *
     * <p>这条链路此前只有手工 smoke 脚本覆盖，页面上的「存入题库」按钮接入后补成自动化用例。</p>
     */
    @Test
    @Order(12)
    void 用例12_本地题目存入题库测试() {
        Account owner = newUser();
        Account other = newUser();

        // 步骤1：一份带本地题目的问卷，题目实例 ID 固定为 q1
        String surveyId = createSurvey(owner.token(), singleQuestionSurvey("待沉淀问卷"));
        String promotePath = "/api/surveys/" + surveyId + "/questions/q1/promote";

        // 步骤2~6：导入成功，内容原样存档
        Api promoted = call(HttpMethod.POST, promotePath, null, owner.token());
        assertEquals(200, promoted.status(), "步骤1 存入题库应返回 200，实际 " + promoted.status() + " " + promoted.message());
        String questionId = promoted.data().path("id").asText();
        assertFalse(questionId.isBlank(), "步骤2 应返回新题目的 ID");
        assertEquals("请选择一个选项", promoted.data().path("title").asText(), "步骤3 题干应与问卷里的题目一致");
        assertEquals(1, promoted.data().path("currentVersionNo").asInt(), "步骤4 导入即生成 v1");
        assertEquals(2, promoted.data().path("currentVersion").path("options").size(), "步骤5 选项应一并带进题库");
        assertEquals("从问卷导入", promoted.data().path("currentVersion").path("changeNote").asText(),
                "步骤6 未指定变更说明时应写入默认说明");

        // 步骤7~8：原问卷不能被改动
        Api survey = call(HttpMethod.GET, "/api/surveys/" + surveyId, null, owner.token());
        JsonNode localQuestion = survey.data().path("questions").get(0);
        assertEquals("local", localQuestion.path("sourceType").asText(),
                "步骤7 导入不应把原问卷的题目换绑成题库引用");
        assertEquals("q1", localQuestion.path("id").asText(), "步骤8 题目实例 ID 不应变化");

        // 步骤9：新题目出现在题库列表里
        Api library = call(HttpMethod.GET, "/api/questions?status=", null, owner.token());
        boolean listed = false;
        for (JsonNode node : library.data()) {
            if (questionId.equals(node.path("id").asText())) {
                listed = true;
            }
        }
        assertTrue(listed, "步骤9 导入的题目应出现在我的题库列表");

        // 步骤10~11：重复导入被拒（幂等）
        Api again = call(HttpMethod.POST, promotePath, null, owner.token());
        assertEquals(409, again.status(), "步骤10 重复导入应返回 409，实际 " + again.status());
        assertTrue(again.message().contains("已加入过题库"), "步骤11 重复导入的提示应说明已经导入过");

        // 步骤12：非问卷创建者不能导入
        Api byOther = call(HttpMethod.POST, promotePath, null, other.token());
        assertEquals(403, byOther.status(), "步骤12 非问卷创建者导入应返回 403，实际 " + byOther.status());

        // 步骤13：已经引用题库的题目不需要再导入
        String[] refQuestion = createLibraryQuestion(owner.token(), "引用题目", "single",
                List.of(option("r1", "是", 1), option("r2", "否", 2)), null);
        String refSurveyId = createSurvey(owner.token(),
                surveyWithRef("引用问卷", libraryRef("q1", 1, refQuestion[0], refQuestion[1], 1)));
        Api refPromote = call(HttpMethod.POST,
                "/api/surveys/" + refSurveyId + "/questions/q1/promote", null, owner.token());
        assertEquals(409, refPromote.status(), "步骤13 引用题库的题目再次导入应返回 409，实际 " + refPromote.status());
    }

    /* ==================================================================== 清理 */

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

        // 历史数据用例直接插入的问卷，ownerId 同样是测试账号，会被这里一并清掉
        List<Document> surveys = mongoTemplate.find(
                Query.query(Criteria.where("ownerId").in(userIds)), Document.class, "surveys");
        List<Object> surveyIds = surveys.stream().map(survey -> survey.get("_id")).toList();
        if (!surveyIds.isEmpty()) {
            List<String> surveyIdStrings = surveyIds.stream().map(Object::toString).toList();
            mongoTemplate.remove(Query.query(Criteria.where("surveyId").in(surveyIdStrings)), "responses");
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

        mongoTemplate.remove(Query.query(Criteria.where("granteeId").in(userIds)), "question_shares");
        mongoTemplate.remove(Query.query(Criteria.where("_id").in(userObjectIds)), "users");
    }
}
