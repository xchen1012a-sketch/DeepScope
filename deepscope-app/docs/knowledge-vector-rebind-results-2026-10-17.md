# 知识库向量模型手动更换与重建结果

依据设计 `docs/superpowers/specs/2026-10-17-knowledge-vector-rebind-design.md` 和实施补充 `docs/superpowers/plans/2026-10-17-knowledge-vector-rebind-implementation.md` 完成。保留仓库重命名与混合工作树更改，没有commit/push/reset，没有新增依赖。

## 完成行为

- 库设置的向量模型从只读展示改为现有AppSelect选择器，标记系统默认；独立“保存模型”不提交未保存的分块参数。空库直接保存，有文档必须二次确认，取消不写配置；确认后转到文档Tab看进度。
- POST `/api/advisor/knowledge/{libraryId}/reindex` 复用编辑/管理权限，返回202和影响文档数/模型/维度；非法端点400，处理中库409。GET同库`embedding-models`仅返回可用向量端点ID、名称、维度和系统默认标记，不返回URL/凭据或要求额外模型管理权限。
- 新增库`index_state`及兼容响应`documentCount/rebuilding`。原地重建在同一短事务完成端点验证与绑定、清除旧向量checkpoint、重置文档状态和DROP旧物理向量表；事务失败回滚旧绑定和旧表，不维护影子索引。
- 统一库→文档锁顺序；模型删除也先锁端点再检查引用，避免刚绑定的目标被并发删除。PgVectorStore缓存按表与维度区分，重置时清理。
- 复用原有完整文字切片和来源重新向量化，没有完整切片才读取源文件；不要求重传。复用正文时同时保留匹配的已发布页面并恢复预览页，清空解析签名以免把旧页面误当新解析配置缓存。
- 批量按容量分批送入现有有界队列，文档多于队列容量不会直接因溢出判失败；投递与关闭互斥，服务关闭清理待投递项。新上传在重建期间被拒绝，前端禁用上传。
- 整个库所有文档就绪前均暂停检索；先完成几篇不提前开放。失败不能因旧文字切片仍存在而误报ready。重启沿既有中断恢复标记failed，仍保持全库暂停；现有单篇重新处理可继续恢复，删除失败文档后若剩余均ready则解除暂停。智能体跳过未完成重建的库，其他库仍可使用。
- 检索在远程重排完成后最终返回前复核参与库的状态及绑定，拒绝换模型期间的旧结果。空库更换后查询直接空结果，不调用向量模型或访问不存在的物理表。

## 验证与审查

- 先复现空库API缺失、前端缺少选择/确认流程，再实现；只读审查另发现远程重排窗口和正文/页面快照错配，已各自用回归复现并修复。清除恢复页的旧解析签名也有RED→GREEN证据。
- 真实独立PostgreSQL：同维度重建、异维度重建、故障回滚后旧表/绑定/状态保留、并发删除目标模型四项通过；原PgChunkIndexTest三项通过。没有对业务库执行重建测试。
- 后端模块完整测试715项通过（通过`io.deepscope.advisor.**.*Test`选择全部backend包，依赖模块仅构建，不重复跑其全量测试）。日志 `/tmp/knowledge-rebind-backend-full.log`。
- 收口后的相关23项通过并打包；最后智能体scope和关闭互斥收口相关22项通过并打包。日志 `/tmp/knowledge-rebind-final-package.log`、`/tmp/knowledge-rebind-runtime-package.log`。
- 前端完整88文件/817项通过。首次全量有1条既有思考过程测试未等待异步缓存刷新，补充等待DOM实际出现后再点击，断言未弱化；重新全量817项通过。日志 `/tmp/knowledge-rebind-frontend-full-final.log`。
- 前端build通过；完整ESLint 0 errors、40 warnings。改动范围内LibraryDetailPage已有activeTab effect依赖警告保留，新增组件无新增警告。日志 `/tmp/knowledge-rebind-build-delivery.log`、`/tmp/knowledge-rebind-frontend-lint-all.log`。
- 最后静态审查指出的解析签名遗漏已修复并精确验证；没有启动浏览器、没有自动调用真实模型或重新处理真实文档。

## 本地运行

更新前确认运行会话0、处理中档案0。已正常关闭旧PID48453，使用本次打包快照 `/tmp/knowledge-rebind-20261017.jar` 启动本地18080；启动日志 `/tmp/knowledge-rebind-runtime.log`。业务库已增加`index_state`，旧库默认ready，没有自动改变模型绑定或触发重建。临时PG55439已关闭。

实际使用：知识库详情→设置→向量模型→保存模型；有文档时确认更换并重建。尚未在用户真实供应商及文档上触发付费重建，运行效果需用户主动确认后观察；不承诺供应商请求永不超时。
