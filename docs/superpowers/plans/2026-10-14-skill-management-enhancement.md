# 技能管理模块完善（ZIP解压容错、文件卡片化与本体编辑）：技术实现方案

> **执行约定**：本次只交付方案，不修改业务代码。用户审查后按检查点实施；先写行为测试并确认失败，再最小实现和验证。未授权不提交、不推送、不迁移数据库、不重启服务。

**Goal**：彻底解决技能创建时 ZIP 拖包报错、文件视觉呈现简陋、手动创建缺少技能本体指令的核心痛点，对齐主流 Agent 平台的规范化技能生命周期体验。

**Architecture**：
- 后端：优化 `SkillPackage` 的解压适配能力（兼容根目录扁平打包与单目录打包），在 `AdvisorSkillController` 中支持手写技能本体 Markdown 直存与独立的 AI 初稿生成端点，统一结构化错误码响应；
- 前端：升级 `SkillsPage.tsx`，将文件上传区重构为具有文件类型图标、文件大小、状态徽标与重选/移除功能的「文件卡片」，手动填写模式扩展为「元数据 + 触发说明 + Markdown 技能本体编辑器」的标准工作台。

**Tech Stack**：现有 React / TypeScript / TanStack Query / Tailwind / Lucide Icons / i18next；Spring Boot / Java ZipInputStream / Jackson。

**改动等级**：中级改动（限于 `technical-advisor-app` 内部的前后端技能交互与解包逻辑，不破坏底层的数据库存储与 Harness 运行模型）。

---

## 1. 范围与覆盖清单

| 用户问题与现状痛点 | 本方案技术交付 | 对应检查点 |
| :--- | :--- | :--- |
| **问题 1：新建技能拖入 zip 包保存提示未上传文件** | ① 增强 `SkillPackage` 解压兼容性（同时支持顶层目录打包与扁平无目录打包）；<br>② 细化 `SkillValidationException`，明确诊断“缺少 SKILL.md”、“缺少元数据”等；<br>③ 控制器返回结构化 JSON 错误，前端精确回显真实原因，拒绝吞错。 | S1 / S2 |
| **问题 2：拖入后仅有名字无图标，样式简陋** | ① 封装 `SkillFileCard`，呈现专用 Zip 归档图标 / Markdown 文档图标；<br>② 显示格式化文件大小（如 `32.4 KB`）；<br>③ 增加状态徽标（“已就绪”）及右侧「更换文件」与「删除」操作。 | S3 |
| **问题 3：手动填写只有名称用途，缺少技能本体** | ① `POST /api/advisor/skills`（JSON 模式）新增 `content` 字段，支持直接存储手写本体；<br>② 前端表单扩展多行 Markdown 技能本体编辑器，并提供「插入规范模版」；<br>③ 新增 `POST /api/advisor/skills/draft-content`，支持在编辑器内点击「AI 规划初稿」实时回填与人工微调。 | S2 / S3 |

---

## 2. 核心技术决策

### 2.1 ZIP 解压容错（为什么用户拖入 zip 经常报错）
底层 `agentscope-core` 的 `SkillUtil.createFromZip` 强制校验了 `separatorIndex > 0`（要求所有 entry 必须在同一顶级目录下），而大部分操作系统（如 macOS/Windows 右键直接压缩文件）打出的 zip 包中，`SKILL.md` 直接位于 zip 根目录（`separatorIndex = -1`），导致直接抛出 `IllegalArgumentException("Zip entries must be under a single root directory.")`。

**技术决策**：
在业务层的 `SkillPackage.java` 中增加智能重构与安全解包逻辑（不修改 core）：
1. 遍历 zip entries：
   - 若检测到 `SKILL.md` 直接位于根路径（扁平打包），自动提取主文件和关联 resources；
   - 若检测到 `rootName/SKILL.md`（标准单目录打包），按标准逻辑处理；
2. 异常细化：
   - 缺少 `SKILL.md` 时抛出明确业务异常：`"压缩包内未找到 SKILL.md，请确保包含技能定义主文件"`；
   - `SKILL.md` 缺少 frontmatter `name` 或 `description` 时抛出：`"SKILL.md 缺少必要的 YAML Front Matter (name/description)"`；
   - `SKILL.md` 正文为空时抛出：`"SKILL.md 技能本体内容不能为空"`；
3. 控制器以 HTTP 400 返回 `{ "code": "invalid_package", "message": "..." }`，前端展示真实 message。

### 2.2 手写技能本体与 AI 辅助的双轨流转
现状是手动模式强行调用大模型生成并在后台直接存盘，用户无法查看和修改大模型生成的内容，也没有地方自己写规则。

**技术决策**：
1. **优先使用手写本体**：用户在前端编辑器手写了 Markdown 正文时，直接作为技能主体落盘；
2. **AI 初稿作为编辑器助手**：用户点击「AI 生成初稿」时，异步调用后端的提取端点，将大模型写出的 Markdown 直接填充到前端编辑器中，用户可以预览、增删改查、调整 Prompt 约束，满意后再点击保存。

---

## 3. 前端交互与组件设计

### 3.1 上传模式（Zip / Markdown）卡片结构
拖拽或选择文件后，将原有单纯的文字替换为 **结构化文件卡片**：
```text
┌─────────────────────────────────────────────────────────────────┐
│  [📁 Zip 图标]  release-tool.zip                       [更换] [🗑]│
│                 32.4 KB · 包含 SKILL.md 与 2 个资源文件          │
│                 ✓ 文件合规，可直接保存                           │
└─────────────────────────────────────────────────────────────────┘
```
- 若发生错误，卡片变红并展示具体原因：
  `⚠ 压缩包格式错误：根目录下未找到 SKILL.md，请检查压缩包文件结构。`

### 3.2 手动模式（三段式工作台）表单结构
```text
技能名称 *
[ code_review_assistant                                         ]

触发场景说明 (When to use) *
[ 当用户需要对 Java/Python 代码进行安全审计、代码异味检查并输出重构建议时触发 ]

技能本体与执行规则 (Instructions / Markdown) *
┌─ 工具栏: [✨ AI 规划初稿] [📋 插入标准模版] ────────────────────┐
│ # 代码审查技能                                                  │
│                                                                 │
│ ## 执行步骤                                                     │
│ 1. 扫描提交中的敏感配置与 SQL 拼接风险                          │
│ 2. 检查复杂度与方法圈复杂度                                     │
│ 3. 按照严重程度（高/中/低）输出优化方案                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. API 契约设计

### 4.1 创建技能（Multipart 模式）
- **路径**：`POST /api/advisor/skills`
- **请求**：`MultipartFile file`
- **成功**：HTTP 201，返回创建的 `SkillSummary`
- **失败**：HTTP 400，返回 `{ "code": "invalid_package", "message": "错误原因说明" }`

### 4.2 创建技能（JSON 模式）
- **路径**：`POST /api/advisor/skills`
- **请求体**：
  ```json
  {
    "name": "code_review",
    "description": "代码安全与重构审查",
    "content": "# 技能本体Markdown内容...",
    "brief": "可选的用途简述"
  }
  ```
- **逻辑**：若 `content` 存在，直接构造技能保存草稿；若 `content` 为空但 `brief` 存在，则由大模型生成。

### 4.3 AI 生成初稿（辅助端点）
- **路径**：`POST /api/advisor/skills/draft-content`
- **请求体**：
  ```json
  {
    "name": "code_review",
    "description": "代码安全与重构审查"
  }
  ```
- **返回**：
  ```json
  {
    "content": "---\nname: code_review\ndescription: 代码安全与重构审查\n---\n\n## 目标\n..."
  }
  ```

---

## 5. TDD 检查点与实施排期

### S1：后端 ZIP 解压容错与异常细化
- [ ] 编写 `SkillPackageTest` 失败测试用例：
  - 测试根目录扁平打包的 zip（无单一顶级目录）；
  - 测试带有一层顶层目录的标准 zip；
  - 测试缺少 `SKILL.md` 的 zip，断言明确的 `IllegalArgumentException` 信息；
  - 测试 `SKILL.md` 缺少 frontmatter 的 zip，断言明确信息。
- [ ] 优化 `SkillPackage.java`，跑通所有测试。

### S2：后端控制器接口升级与结构化响应
- [ ] 编写 `AdvisorSkillControllerTest` 失败测试用例：
  - 上传不合规 zip 时返回包含 `{ code, message }` 的 400 JSON 响应；
  - JSON 模式传入 `content` 时直接创建草稿，无需调用大模型；
  - 调用 `/api/advisor/skills/draft-content` 正确返回 AI 建议的 Markdown 正文。
- [ ] 改造 `AdvisorSkillController.java`，跑通接口测试。

### S3：前端文件卡片化与手写本体编辑器
- [ ] 编写前端 `SkillsPage.test.tsx` 单元测试：
  - 验证拖入 zip/md 展现文件卡片（含图标、格式化大小、清除按钮）；
  - 验证上传失败时将后端错误 message 精准呈现；
  - 验证手动模式下名称、用途、技能本体三项联动及保存提交；
  - 验证点击「AI 规划初稿」正确将内容回填入 Markdown 编辑器。
- [ ] 改造 `SkillsPage.tsx`，补齐中英国际化文案（`zh-CN/app.json` 与 `en/app.json`）。

### S4：端到端与全量门禁验收
- [ ] 执行前端全量测试：`npm test`、`npm run lint`、`npm run build`
- [ ] 执行后端代码格式化与全量门禁：`mvn spotless:apply`、`mvn test`、`mvn spotless:check`
- [ ] 启动服务并验证真实页面交互。
