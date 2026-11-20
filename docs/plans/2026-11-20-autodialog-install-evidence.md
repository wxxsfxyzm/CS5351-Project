# 第二批审批草案：单 APK 的 `InstallEvidence` 转换与离线测试

## 候选接入契约与逐字段来源

追踪项：`REQ-IX-FR-001/002`、`REQ-IX-SEC-004`、`REQ-IX-INV-006`、`ADR-001 Option B`。下列映射是审批候选；实现前须以已批准的 D1 契约逐项核对字段、状态及对应 fit criteria。

候选转换接收**同一次分析的 `PackageAnalysisResult`、已明确选中的普通单 APK `BaseEntity`，以及 D1 要求的现有结构化检查结论**，组装现有 `InstallEvidence`。转换只使用内存中的分析结果，不读取 APK、不重做 Android 检查、不触发 Advisor 或安装。不能确认候选归属或输入超出单普通 APK 范围时，按批准的契约拒绝转换或返回明确的不可用结果，不从候选列表任取第一项。

| 数据项 | 候选来源与转换规则 |
| --- | --- |
| 包与候选身份 | 由 `PackageAnalysisResult` 中的 `appEntities`、`identityStatus`、`packageName` 与显式选中的 `BaseEntity` 确认归属。`packageName` 仅作本地数据。 |
| APK 文件摘要 | 使用选中 `BaseEntity.fileHash`，缺失时不填造、不另算，也不拿 `signatureHash` 替代。保留现有 `completeness()` 对缺少文件摘要返回 `UNREADABLE` 的行为。文件摘要不出站。 |
| 版本与安装状态 | 候选版本取 `BaseEntity.versionCode` / `versionName`；现装状态来自 `installedAppInfo`。如 D1 要求升级、降级等结论，复用现有结构化版本比较结果，不在转换器另写比较规则。 |
| 签名状态 | 先看 `signatureCheckPerformed`，再按 D1 映射 `signatureMatchStatus` / `signatureAnalysis` 中已有的结构化结果。区分未检查、已确认匹配和已确认不匹配；原始签名及签名摘要不出站。 |
| 权限 | 使用选中 `BaseEntity.permissions`。`null` 表示未读到，已读取的空列表表示确认无权限；必须在构造证据前处理这一区别，不能由默认 `emptyList()` 吞掉。如果 D1 无法表达两者，暂停并请组长确认契约。 |
| SDK、ABI 与平台兼容性 | `minSdk`、`targetSdk` 是字符串，`arch` 是候选 APK 的架构信息；这些是元数据，不是设备兼容结论。兼容状态只消费已确认的结构化检查输出；没有明确结论时不得因无告警而判为兼容。 |
| 来源类型与 Xposed 标记 | 若 D1 定义对应字段，直接使用 `BaseEntity.sourceType` 与现有 `isXposedModule` 推导，不新增识别规则。 |

出站仅在离线测试中调用 **#13 现有 request builder**，断言其产物符合既有白名单。本批不新增请求接口，也不发送任何模型请求。

## 已有事实与待确认输入

- `ActionHandler.analyse`（L382–429）将成功的 `List<PackageAnalysisResult>` 写入 `session.analysisResults`；这确定了证据来源，尚未确定生产调用点。`InstallerViewModel`（L597–602）在 `InstallPrepare` 后调用 `install()`，属于组长最后一批的集成边界。
- `domain/engine/model/packageinfo/PackageAnalysisResult.kt`（L12–24）和 `AppEntity.kt`（L22–46）提供上述分析数据。`domain/engine/usecase/AnalyzeInstallStateUseCase.kt`（L26–74、166–168）已有版本、签名及最小 SDK 检查；当前尚未确认 ABI／平台兼容性应消费哪个结构化输出。
- #13 已有 Advisor、Groq、Prompt 和 JSON 校验，可复用；真实类型编译及固定模型响应测试已有证据，Groq 实网与真机仍未验证。#16 仍是未合并草稿，已有 Policy Engine 和 6 项测试；已报告本机全量 259 项、Spotless 和两种 Debug 构建通过。远程 Release 因 runner shutdown 被取消，仍未验证，不计为政策源码失败。这些是前序证据，不是第二批验证结果。

**请组长确认三个接口问题：**

1. 以哪一版 D1 契约为准？其中“权限未读到”和“签名未检查”分别映射到什么字段或状态；若目前无法区分，应采用哪种已有的不可用结果？
2. ABI／平台兼容性应消费哪个现有类型及字段？该输出缺失时，D1 对应的未知状态是什么？
3. 上层是否会直接传入同一次分析中已确认归属的单 APK `BaseEntity`？若无法确认归属，应返回哪种已有的不可用结果？

## 文件范围

- **生产代码：**候选是在 `app/src/main/` 对应的现有 domain/engine 包下新增一个窄范围 Kotlin 转换文件。待确认 D1 类型位置和依赖方向后，在审批前锁定实际文件路径。本批只提供转换入口，不接生产调用点。
- **离线测试：**在 `app/src/test/` 对应包下增加转换测试；在 #13 现有 request builder 的离线测试位置补充一条转换产物的白名单回归。审批前锁定测试路径和用例。
- **不在范围内：**修改 Advisor、Groq、Prompt、JSON 校验、request builder 生产代码或 #16 Policy Engine；修改 `ActionHandler`、`InstallerViewModel`、AutoDialog、安装按钮、Cancel/Continue、复验、去重或实际安装提交。Splits、AutoNotification、安装 Session、Root／Shizuku 均不接入。

## 具体验收与离线测试

| 验收项 | 离线用例 |
| --- | --- |
| 单 APK 输入和字段映射可追踪（`REQ-IX-FR-001/002`） | 用真实工程类型构造归属明确的单 APK 分析结果，逐字段断言候选元数据及已提供的结构化结论；加入未选中、候选归属错误和非单 APK 负例，验证不会选取其他候选或产出正常证据。 |
| 缺失信息不会冒充已确认结果（`REQ-IX-FR-001/002`、`REQ-IX-INV-006`，以 D1 核准为准） | 对比权限 `null`、已确认空列表及非空列表；对比签名未检查、已确认匹配和已确认不匹配；对比明确兼容、不兼容及没有结构化结论。另用不同的文件摘要和签名摘要验证来源，并验证有签名摘要但无文件摘要时仍为 `UNREADABLE`。 |
| 证据满足出站白名单，转换不触发副作用（`REQ-IX-SEC-004`、`REQ-IX-INV-006`、`ADR-001 Option B`） | 在输入中放入可识别的包名、文件摘要、签名原值与签名摘要，调用现有 request builder，按既有白名单断言产物字段及允许值，并确认这些标记未泄露。用例直接调用转换器与 builder，无网络或模型依赖；结构审查确认转换器不依赖 Advisor、网络客户端或安装后端。 |

获批后先运行新增离线用例，再以 #16 的 `5718ad2` 为基线重跑既有测试套件（含 #13 固定模型响应测试和 #16 六项测试）、Spotless、`./gradlew assembleUnstableDebug` 与 `./gradlew assemblePreviewDebug`。审批时从仓库既有配置锁定单测和 Spotless 的实际任务名；报告各项命令与结果、任何未执行项及原因。259 项是历史基线记录，不是预设通过数。

## 阻塞与审批次序

1. 按用户 2026-11-20 的最新决定，以 #16 的提交 `5718ad2` 作为第二批本地基线，无需等 GitHub 合并。远程 #16 仍是草稿。原 Release 作业因 runner shutdown 取消，本批不以该上游作业作为启动前提，也不把 Release 记为已验证。
2. 由组长回答上述三问；对照获批的 D1、ADR 和需求锁定字段映射、文件路径、测试矩阵及验证命令。如现有契约无法区分未知和已确认状态，先解决契约，不改 Advisor 或 #16 引擎来绕过。
3. 取得对**第二批计划和测试**的明确人工批准后，才实现转换与离线测试。验证通过后交人工审阅；后续 PR 标题和正文统一使用英文。

后续运行时接线依赖另批完成网络、同意和运行时密钥门禁，再由组长完成 AutoDialog 及安装流程集成。

## 已确认的实施边界

用户在收到计划及两个接口确认点后明确回复「批准」。沿用 PR #16 的提交 `5718ad2`，使用调用方传入的显式单 APK 选中项及结构化平台和 ABI 结果。缺少结构化兼容性结论时使用 `UNKNOWN`。权限未读取时返回不可用于 Advisor 的不完整结果，确认无权限时保留空列表。测试用现有请求构建器验证成功转换结果的出站边界，放在同一新增测试文件。

- 生产文件：`app/src/main/java/com/rosan/installer/domain/ai/usecase/AutoDialogInstallEvidenceMapper.kt`
- 测试文件：`app/src/test/java/com/rosan/installer/domain/ai/usecase/AutoDialogInstallEvidenceMapperTest.kt`
- 验证：`spotlessCheck`、`:app:testUnstableDebugUnitTest`、`assembleUnstableDebug`、`assemblePreviewDebug`。
