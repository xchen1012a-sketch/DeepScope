# 前端工程规范

适用模块：`technical-advisor-app/frontend`（技术参谋演示前端，源自原平台控制台整体剥离）。源码目录：`src/`。

技术基线：React 18 + TypeScript 5.7 + Vite 6、Tailwind CSS 4 + Radix UI + class-variance-authority / clsx / tailwind-merge、TanStack Query 5（服务端状态）、React Router 6、i18next + react-i18next（国际化）、Vitest + ESLint（flat config）+ Playwright。

## 门禁规则（工具强制）

完整命令清单见 [gates.md](gates.md)。

- **【门禁】Lint**：`npm run lint`（ESLint flat config，`typescript-eslint` recommended + `react-hooks` + `react-refresh`）必须零 error；`react-hooks/rules-of-hooks` 为 error 级，hook 规则违例直接阻断。
- **【门禁】类型检查**：`npm run build` 内含 `tsc --noEmit`，TypeScript 编译错误视为构建失败。
- **【门禁】测试**：`npm test`（Vitest）必须通过；涉及关键用户流程的变更须跑 `npm run test:e2e`（Playwright）。

## 人工规范（评审约束）

### 目录职责

- **【人工】分层组织**：`src/api/` 只放 API 客户端与请求/响应类型；`src/app/` 放应用外壳与全局布局；`src/components/` 放跨 feature 复用的通用组件；`src/features/` 按功能域垂直组织（页面私有组件、hooks、状态就近放置）；`src/lib/` 放与 UI 无关的通用工具；`src/pages/` 放路由页面组件。通用组件（`components/`）尽量不依赖具体 feature 的内部模块（存量如 ChatPanel 存在例外，不强制回改，但不得新增扩散）。

### 国际化

- **【人工】文案零硬编码**：所有用户可见文案必须经 `t()` 输出，禁止在组件中硬编码中英文字符串。
- **【人工】词典结构**：词条统一维护在 `src/i18n/locales/{en,zh-CN}/` 下，按 `app` / `common` 两个命名空间分文件（默认命名空间 `app`）；默认语言 zh-CN，回退 en；语言偏好持久化于 localStorage key `controlplane.console.locale`。新增文案必须同时补齐 en 与 zh-CN 词典。

### 状态与数据

- **【人工】服务端状态**：服务端数据一律通过 TanStack Query 获取与缓存（query key 按资源维度稳定命名），禁止手工同步服务端缓存到本地 state。
- **【人工】依赖克制**：新增依赖须确认与现有库能力不重叠（UI 基础组件用 Radix、样式用 Tailwind、图标用 lucide-react、Markdown 渲染用 react-markdown），引入新的大类库属于中级以上改动。
- **【人工】类型收敛**：`@typescript-eslint/no-explicit-any` 当前为 off，`any` 的使用由评审把关——优先精确类型、泛型或 `unknown` + 收窄，函数签名禁止用 `any` 掩盖真实的入参/返回结构。

### 样式与组件

- **【人工】样式约定**：使用 Tailwind 原子类 + `cn()`（clsx + tailwind-merge）组合；可复用组件的变体用 class-variance-authority 定义，禁止内联 style 实现可由类表达的外观。
- **【人工】组件边界**：交互基础组件优先组合 Radix 原语而非自行实现焦点管理/键盘导航等无障碍行为。

### 列表统一写法

- **【人工】标准表格**：管理列表复用 `src/components/DataTable.tsx` 与 `Pagination.tsx`，不得在 feature 内重复实现表头、外框、空态和分页。特殊行保留 `renderRow` 扩展，沿用相同列间距。
- **【人工】行操作**：优先配置 `DataTable.actions`，特殊行使用 `TableActions.tsx`；统一采用工具/MCP页的小尺寸描边按钮，纯文字或图标加文字；「编辑」操作固定为纯文字，不带图标。禁止页面自行拼接操作栏按钮尺寸、间距和颜色。
- **【人工】用户/角色列表**：通过 `DataTable.actionsAppearance="text"` 复用芋道式文字操作变体（编辑、删除、更多），新建入口放 `toolbarActions`；搜索条件横排、紧凑且仅提交后查询，使用 `TableToolbar` 的标签模式。其他列表继续采用默认描边操作，不在 feature 自写第二套按钮或表格样式。
- **【人工】操作语义**：常用操作直接显示，低频操作显式 `overflow`；危险操作 `tone: 'danger'` 排最后，图标操作必须提供清晰 `label`。权限通过 `hidden`，不可用通过 `disabled`，请求中通过 `loading`；业务确认与请求留在 feature。
- **【人工】搜索与状态**：标准搜索复用 `TableToolbar.tsx`，输入为草稿，按钮/回车才提交。列表加载、错误与重试、空结果、分页统一由 DataTable 承载；错误不可伪装成空列表。
- **【人工】按需查询**：管理列表通过 `usePagedList` 调用后端分页接口，携带 `page`、`size`、`q` 和生效筛选条件。搜索提交、重复搜索、翻页、修改页容量与筛选均执行服务端查询；禁止取全量主列表后再用 `filter/slice` 实现搜索或分页。`DataTable` 只展示当前页与服务端总数，列表页缓存不得覆盖选择器缓存。
- **【人工】能力选择器**：智能体详情与创建页的工具/技能/知识库选择器同样复用 `DataTable`、`TableToolbar` 与标准分页，禁止自建搜索栏、列表容器与分页；分页接口额外携带 `purpose=selection` 与 `agentId`/`draftId`（`selectionListParams`），加载、失败重试、空结果与清除条件由列表统一承载。
- **【人工】弹窗结构**：弹窗统一用 `DialogHeader` + `DialogBody` + `DialogFooter` 三段，标题区与操作区不加 `border-b`/`border-t` 分隔线，靠间距与留白分区；底部按钮吸底由 `DialogFooter` 承担，页面不得自行拼操作区样式。
- 产品格式与示例见 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md` 的列表规范章节。
