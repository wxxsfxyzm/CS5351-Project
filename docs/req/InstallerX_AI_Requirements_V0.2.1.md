# InstallerX AI 辅助安装组件需求规格说明书

**版本 V0.2.1（rev.3）** · 小组评审草案 · 2026-11-14

> 本版合并了 V0.1（27 条）与课件格式 registry（22 条），去重后 **41 条**；Markdown 与 [requirements_v0.2.1.yaml](requirements_v0.2.1.yaml) 由同一构建脚本生成，字段级同步。YAML 为唯一机器可读源。

## 0. 本版变更（相对 V0.1）

1. **格式**：YAML 改为课件 block 风格；`type` 改英文受控值；`status` 统一 `conceptual`；ID 加 `REQ-IX-` 前缀。
2. **不变量一等化**：新增 `INV-001`～`INV-007` 七条 `type: invariant` 需求，带 `enforcing_component` 与负向测试。
3. **修依赖**：拆掉 `FR-005 ↔ SEC-002` 环；修正 `REL-002→FR-009`、`SEC-005→FR-004`、`FR-007→FR-006` 三处方向；全图重新校验无环。
4. **补决策表**：从 6 行扩为 10 行，明确证据、建议、策略、人工四轴，补“证据完整 + LOW + 自动规则未满足”“拒绝同意”“证据缺失”三类空洞，并把决策与处置分列。
5. **冻结范围**：`SCOPE-001` 写死 AutoDialog + Authorizer.None 演示后端 + session 旁路 non-goal；关闭 AI 语义落为 `FR-012`；同意流程落为 `FR-011`。
6. **补缺口**：`QUAL-001` 可解释、`ARCH-001` 单一收口、`ARCH-002` 双 UI、`DATA-001` 审计完整性、`VER-001` 可执行追踪。
7. **验证诚实化**：每条带 `verification.method/test/evidence`，未写测试一律 `planned`，不再宣称“已可映射到测试文件”。
8. **统一重试与预算**：每个安装操作最多 2 次模型调用（第 1 次自动、第 2 次仅用户触发），不做自动重试；15 秒为单次尝试预算（AWAITING_AI → 决定落库）。
9. **拒绝外发与关闭 AI 分开**：拒绝同意 = 零调用 + `REQUIRE_REVIEW`；AI 关闭 = 基线行为 + `AI_DISABLED`；模型失败 = `REQUIRE_REVIEW`；三者分别验收。
10. **证据缺失禁止提交**：`UNREADABLE` / 绑定字段缺失 → `RECOLLECT`，确认按钮不得提交；来源未知但 APK 字节、哈希、目标用户可核验时仍可人工审核。
11. **限缩 `ARCH-001`**：收口只约束 `SCOPE-001` 路径；范围外路径要求零 AI 调用，不要求改造。
12. **决策表入 YAML**：新增 `decision_rules`，Markdown 表由同一数据生成；决策与处置分列。
13. **拆分 `AIQ-001` / `QUAL-001`**：自动只查引用存在性，人工评估理由真实性与相关性。
14. **补逐条 ID 映射**：旧两份 registry 与新 ID 的对应关系见 §10。
15. **rev.3 规则优先级**：决策规则按 硬性禁止 → 复验失败 → 证据缺失 → 拒绝外发 → 常规 排序；校验脚本新增跨场景首命中用例：拒绝外发+不可读→`RECOLLECT`、复验失败+不可读→`INVALIDATED`、完整证据+拒绝外发→`AWAIT_HUMAN`。

## 1. 目的与依据

在 InstallerX Revived 现有安装流程上增加 AI 安装前风险建议，并用确定性规则和人工审核控制是否继续安装。本文用于需求评审、ASR/ADD 分析与验收测试设计。

依据：V0.2 方向文档 §3–§6；CS5351 2026-2027 RE 课件 p.41（Invariants）、p.109–p.111（Specification/Reference Solution/Validation）与 `3_Requirement Engineering/reg-registry-for-VHIS-B1.yaml` 的字段约定。课程 Project Requirement Sheet 尚未核对，本版不宣称满足 D1–D9 全部提交要求。

## 2. 目标、角色与边界

目标是让用户获得有证据支持的安装风险建议，并确保模型异常、对象变化或中断时不会产生未经有效批准的安装。

| 角色 | 需要 |
|---|---|
| 安装用户 | 了解风险，确认或取消待审核请求，查看结果 |
| 小组开发者 | 在固定基线上扩展 AI 功能，追踪新增代码和行为 |
| 验证及评审成员 | 通过可重复测试核对需求、安全边界与恢复行为 |
| 外部模型服务 | 接收经同意的限定数据并返回建议，作为外部依赖 |
| Android 系统及授权服务 | 按既有权限约束执行安装并返回结果 |

### 2.1 本版冻结的范围（对应 `SCOPE-001`）

| 维度 | v0.2.1 决定 | 理由 |
|---|---|---|
| 入口 | `InstallerActivity` 的 `VIEW` / `INSTALL_PACKAGE` / `SEND` | 组长的边界：只管自己发起的安装 |
| 安装模式 | `InstallMode.AutoDialog`（安装准备页 500ms 后自动提交） | 唯一同时具备 UI 与自动提交的路径，便于验收 |
| 演示后端 | `Authorizer.None`（系统 `PackageInstaller`） | 模拟器无 Shizuku binder；此路径已实测可完成真实更新 |
| 非目标 | 其他应用发起的 session（含 `ActionHandler` 自动批准）、模块、批量/分包、卸载/解包/网络 URL、Root/Shizuku 全覆盖、恶意软件检测、多 Agent、云平台 | 控制 v1 规模；session 旁路写入威胁模型与 future work |
| AI 关闭 | 保持既有行为，零模型调用，审计记 `AI_DISABLED`；不得削弱既有检查（`FR-012`） | 防止“关开关即绕过” |
| 首次外发 | 明确同意后才调用模型；拒绝则零调用 + `REQUIRE_REVIEW`（硬性禁止仍 BLOCK），不等于关闭 AI（`FR-011` / `SEC-004`） | 隐私与网络边界 |

说明：`Authorizer.None` 下，InstallerX 提交后 Android 仍会显示系统安装确认页（`PackageInstaller`）；这是平台自身行为，不属于 AI 审批绕过。

## 3. 术语

- **风险级别**：`LOW | MEDIUM | HIGH | UNKNOWN`，由接口契约冻结；模型只输出建议，不构成授权。
- **建议状态**：`VALID_LOW | VALID_MEDIUM | VALID_HIGH | VALID_UNKNOWN | INVALID | UNAVAILABLE`。
- **证据状态**：`COMPLETE | KEY_UNKNOWN | CONTRADICTORY | UNREADABLE`；`KEY_UNKNOWN` 仅指 `INV-003` 绑定字段中任一缺失/不可验证。
- **安装操作 ID（operation ID）**：一次用户发起的安装动作；用户重试分析不改变该 ID，最多产生一次自动提交。
- **分析尝试 ID（attempt ID）**：一次模型分析调用；同一 operation 最多两次（1 次自动 + 1 次由用户触发）。
- **决策**：`ALLOW_AUTO | REQUIRE_REVIEW | BLOCK`；**处置**：`EXECUTE_ONCE | AWAIT_HUMAN | BLOCKED | RECOLLECT | INVALIDATED | RESULT_UNKNOWN`；决策是策略结论，处置是执行动作，二者分列。
- **有效批准**：与当前 operation ID、attempt ID、APK SHA-256、目标用户、安装模式、后端、配置与策略版本一致，绑定字段齐全，且复验通过的人工批准。
- **优先级**：Must（v1 验收必需）· Should（核心闭环后优先）· Could（扩展）· Won’t（本版排除）。
- **状态**：`conceptual`（已设计未实现）· `implemented` · `future`。本版全部 `conceptual`。

## 4. 关键用例

| 用例 | 前置条件 | 正常或替代结果 |
|---|---|---|
| UC-01 自动审批 | 受支持 APK、AI 开启、已同意、AutoDialog | 收集证据 → 建议 → 策略 → ALLOW_AUTO → 安装并审计 |
| UC-02 人工审核 | 判定 REQUIRE_REVIEW | 展示证据/建议/分歧 → 批准后复验执行；取消则结束 |
| UC-03 模型故障 | 超时/断网/无效输出 | 降级人工审核；硬性禁止保持 BLOCK |
| UC-04 对象变化 | 批准后 APK/配置/用户/策略变化 | 旧批准失效，重新收集与审批 |
| UC-05 中断恢复 | 审批或安装中进程终止 | 恢复最后持久状态；未知结果标记，不自动重放 |
| UC-06 同意与开关 | 首次外发 / 拒绝同意 / 关闭 AI | 同意后调用；拒绝 → 零调用 + `REQUIRE_REVIEW`；关闭 AI → 基线零调用并审计 `AI_DISABLED` |

## 5. 需求

以下 41 条按类型分组。每条含唯一 ID、类型、优先级、陈述、验收标准、来源、依赖、候选强制组件与验证方式。`planned` 表示测试尚未实现。

### 5.1 功能需求

#### REQ-IX-FR-001 接收审批请求

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：在 AI 审批启用且路径受支持时（InstallerActivity 发起、InstallMode.AutoDialog），系统应为每个单 APK 请求生成唯一安装操作 ID（operation ID），每次模型分析生成尝试 ID（attempt ID），并在任何安装后端调用前启动审批。
- 验收标准（`integration + negative`）：提交一个受支持 APK，产生可查询的 operation ID；审批未完成时安装后端调用次数为 0；同一 operation 的用户重试保持 operation ID 不变、只新增 attempt ID。
- 来源：V0.2 §5.1。
- 依赖：无。
- 候选强制组件：ApprovalCoordinator。

#### REQ-IX-FR-002 收集安装证据

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应收集 APK SHA-256、包名、版本、声明权限、签名检查结果、已安装应用签名比较结果、来源信息、目标用户、安装模式及影响安装的配置快照；无法确定的字段标记为 UNKNOWN，不得推测。
- 验收标准（`unit + fixture`）：已知 APK 的提取结果与固定样例一致；无法确定的来源显示 UNKNOWN 且不生成来源事实；证据包含目标用户与安装模式。
- 来源：V0.2 §4.2。
- 依赖：REQ-IX-FR-001。
- 候选强制组件：InstallEvidenceCollector。
- 备注：来源信息缺失记 UNKNOWN，不单独判为 KEY_UNKNOWN；KEY_UNKNOWN 仅指 INV-003 绑定字段缺失或不可验证。

#### REQ-IX-FR-003 真实模型分析

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应在取得外发同意且联网开关允许时，将白名单字段发送给一个真实模型服务，取得包含模型标识、Prompt 版本、风险级别、理由和证据引用的结构化建议；每次尝试（attempt）只调用一次模型，不自动重试。
- 验收标准（`integration (online) + offline fixed response`）：一次在线集成返回模型标识、Prompt 版本、调用状态和符合契约的建议；离线测试使用固定响应；未同意或断网时调用次数为 0；超时后不产生第二次自动调用。
- 来源：V0.2 §5.1。
- 依赖：REQ-IX-FR-002, REQ-IX-FR-011, REQ-IX-SEC-004。
- 候选强制组件：AIRiskAdvisor。

#### REQ-IX-FR-004 验证模型输出

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应校验模型响应的结构、必填字段、枚举、长度和证据引用，拒绝执行指令、未知操作字段、伪造证据引用和越界内容，并将响应标记为 INVALID。
- 验收标准（`unit negative`）：无效 JSON、缺字段、非法风险级别、超长、伪造引用和执行指令样例均判为 INVALID，且不产生自动批准。
- 来源：V0.2 §4.2。
- 依赖：REQ-IX-FR-003。
- 候选强制组件：AIOutputValidator。

#### REQ-IX-FR-005 计算审批结果

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应仅依据生效策略版本和已验证建议，将请求判定为 ALLOW_AUTO、REQUIRE_REVIEW 或 BLOCK，并返回规则 ID 与理由；硬性禁止优先于任何建议或人工确认。
- 验收标准（`decision-table + property`）：决策表每个分支均有用例；相同证据、建议和策略版本产生相同决定；硬性禁止始终 BLOCK 且后端调用为 0。
- 来源：V0.2 §4.2。
- 依赖：REQ-IX-FR-004, REQ-IX-SEC-002。
- 候选强制组件：DeterministicPolicyEngine。

#### REQ-IX-FR-006 人工审核

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应展示包名、版本、签名检查、重要权限（运行时危险权限、无障碍、设备管理及安装未知应用相关权限）、AI 建议、策略理由及两者分歧，允许用户批准或取消 REQUIRE_REVIEW 请求；RECOLLECT 状态下不得提供可提交的确认操作。
- 验收标准（`UI + negative`）：批准前后端调用为 0；取消后为 0；BLOCK 页面无继续安装操作；RECOLLECT 页面确认按钮禁用且后端 0；分歧显示为一致/升级/降级三态之一。
- 来源：V0.2 §5.1。
- 依赖：REQ-IX-FR-005。
- 候选强制组件：HumanReviewHandler。

#### REQ-IX-FR-007 受控安装

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应仅在 ALLOW_AUTO 或有效人工批准后，经唯一授权入口调用既有安装后端；提交前必须重新核对全部审批绑定字段，任一缺失或变化即禁止提交。
- 验收标准（`integration + negative`）：两类有效批准均可进入测试后端；BLOCK、待审核、失效、取消请求均不能进入；复验失败或缺任一绑定字段时后端调用为 0。
- 来源：V0.2 §4.2。
- 依赖：REQ-IX-FR-005, REQ-IX-SEC-003。
- 候选强制组件：InstallationGateway。

#### REQ-IX-FR-008 反馈和取消

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应展示审批与安装进度，允许在提交前取消；已提交后应显示平台实际状态（含 RESULT_UNKNOWN），不得把未知结果显示为未安装或可撤销。
- 验收标准（`UI + negative`）：模型等待/人工审核阶段取消后无提交；提交后取消不把未知结果标为未安装；状态与平台核对一致。
- 来源：V0.2 §6 阶段五。
- 依赖：REQ-IX-FR-001。
- 候选强制组件：InstallerSession UI。

#### REQ-IX-FR-009 决策审计

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：系统应按 operation ID 与 attempt ID 保存证据摘要、APK 哈希、安装模式与后端、配置与策略版本、模型与 Prompt 版本、建议或失败类型、规则 ID、最终决定、人工操作、时间戳和安装结果；不得保存凭据。
- 验收标准（`audit replay`）：正常、BLOCK、人工批准、模型失败四类记录均可还原决策依据；审计中无 token/凭据；记录 append-only。
- 来源：V0.2 §3.3。
- 依赖：REQ-IX-FR-005, REQ-IX-REL-002。
- 候选强制组件：DecisionAuditRecorder。

#### REQ-IX-FR-010 查询审计

- 类型：`functional`（功能） · 优先级：Should · 状态：conceptual
- 需求：用户应能在应用内按请求查看审批记录、最终规则、人工操作和安装结果，并导出为机器可读格式。
- 验收标准（`UI + export`）：两个不同请求的记录互不混用；导出包含 FR-009 全部字段；无权限用户不可读。
- 来源：V0.2 §3.3。
- 依赖：REQ-IX-FR-009。
- 候选强制组件：AuditQuery UI。

#### REQ-IX-FR-011 外发数据同意

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：首次向外部模型发送数据前，系统应展示接收方、字段清单、用途与保留方式并取得明确同意；拒绝或撤回后不得发起模型调用，不得自动批准，转入 REQUIRE_REVIEW（硬性禁止仍 BLOCK）；该路径不等于关闭 AI。
- 验收标准（`UI + counter`）：首次使用出现同意页；同意后请求仅含白名单字段；拒绝/撤回后模型调用为 0、决定为 REQUIRE_REVIEW；与 FR-012（AI 关闭=基线）分别有验收用例；同意与撤回写入审计。
- 来源：V0.2 §9 + 隐私风险。
- 依赖：无。
- 候选强制组件：ConsentGate。

#### REQ-IX-FR-012 AI 开关语义

- 类型：`functional`（功能） · 优先级：Must · 状态：conceptual
- 需求：AI 审批开关关闭时，系统应保持既有安装行为，不发起模型调用，并在审计中记录 AI_DISABLED；开关不得削弱既有签名、黑名单、权限与策略检查。
- 验收标准（`config matrix + negative`）：关闭后模型调用为 0、既有检查仍执行、审计含 AI_DISABLED；该路径与 FR-011 的拒绝同意路径分别测试；任何开关组合下既有检查通过率与基线一致。
- 来源：OI-07（本版决议）。
- 依赖：REQ-IX-FR-011。
- 候选强制组件：Settings + ConsentGate。

### 5.2 不变量

#### REQ-IX-INV-001 AI 无直接特权执行能力

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：AI 组件仅返回建议数据，不得持有或调用安装、卸载、Root、Shizuku 或任何安装后端能力。
- 验收标准（`static + dynamic negative`）：静态检查：AI 适配器包不引用 InstallationGateway / AppInstallerRepository；动态：返回 install/approve 指令的模型桩触发后端 0 次。
- 来源：V0.2 INV-001。
- 依赖：无。
- 候选强制组件：ApprovalCoordinator。

#### REQ-IX-INV-002 建议不覆盖确定性禁止

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：AI 建议与人工确认均不得覆盖生效中的硬性禁止规则；签名异常、黑名单、禁止降级、test-only 等规则由确定性代码执行。
- 验收标准（`negative`）：AI 返回 LOW 且用户确认时，被禁止 APK 仍为 BLOCK；后端调用 0；禁用 AI 后同一规则仍 BLOCK。
- 来源：V0.2 INV-002。
- 依赖：无。
- 候选强制组件：DeterministicPolicyEngine。

#### REQ-IX-INV-003 批准绑定安装对象

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：批准应绑定 operation ID、attempt ID、APK SHA-256、包名、目标用户、安装模式、安装后端、配置快照和策略版本；任一绑定项缺失或变化即失效，提交前必须复验。
- 验收标准（`negative + TOCTOU`）：逐项修改绑定字段，旧批准均被拒绝；证据读取与提交之间的替换尝试不能安装未批准字节；复验通过率 100%。
- 来源：V0.2 INV-003。
- 依赖：REQ-IX-FR-002。
- 候选强制组件：ApprovalStateStore。
- ASR 理由：决定审批状态模型、执行边界与提交前复验，是安全链的核心结构约束。

#### REQ-IX-INV-004 模型故障不静默批准

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：模型超时、断网、限流、服务错误、无效输出或不可用时，系统不得自动批准；有硬性禁止时保持 BLOCK，否则进入 REQUIRE_REVIEW。用户拒绝外发同意不属于模型故障，按 FR-011 处理（零调用、REQUIRE_REVIEW）。
- 验收标准（`fault injection`）：每类故障注入后自动执行 0 次；硬性禁止仍 BLOCK；界面给出故障原因与人工入口。
- 来源：V0.2 INV-004。
- 依赖：无。
- 候选强制组件：ApprovalCoordinator。
- ASR 理由：塑造失败处理结构与降级路径，影响协调器、UI 和审计三处。

#### REQ-IX-INV-005 重复事件最多一次提交

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：同一安装操作 ID 在重复事件、并发回调、进程恢复和用户重试下，最多产生一次自动提交；模型调用重试（attempt）不改变该约束。
- 验收标准（`concurrency + recovery`）：同一 operation ID 并发 20 次执行事件，后端提交 ≤1；恢复重放不增加提交；用户重试只新增 attempt ID，不新增提交权。
- 来源：V0.2 INV-005。
- 依赖：REQ-IX-REL-002。
- 候选强制组件：ApprovalStateStore。

#### REQ-IX-INV-006 策略判定独立于模型

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：策略判定必须独立于模型可用性：无模型时仍能确定性给出 BLOCK 或 REQUIRE_REVIEW；策略代码不得依赖模型 SDK 或网络。
- 验收标准（`static + unit`）：断开模型/注入空适配器后策略单测全通过；策略包静态检查无模型/网络依赖；相同输入输出稳定。
- 来源：课程 deterministic cage + FR-005。
- 依赖：无。
- 候选强制组件：DeterministicPolicyEngine。
- ASR 理由：强制模型与策略分离，决定模块边界和可测试性，是 AI 笼子的结构基础。

#### REQ-IX-INV-007 AI 不降低确定性限制

- 类型：`invariant`（不变量） · 优先级：Must · 状态：conceptual
- 需求：AI 建议不得降低确定性策略给出的限制：基础策略为 REQUIRE_REVIEW 或 BLOCK 时，AI 只能维持或提高限制；仅当基础策略为 ALLOW_AUTO 且 AUTO_RULES_MET 时，才允许自动批准。
- 验收标准（`decision-table negative`）：基础策略 REVIEW + AI LOW + rules met → 仍为 REQUIRE_REVIEW；基础 ALLOW_AUTO + AI LOW + rules met → ALLOW_AUTO；基础 BLOCK + AI LOW + 人工批准 → 仍为 BLOCK；均无越级自动提交。
- 来源：旧 docs registry INV-005；V0.1 正文 INV-005；OI-04。
- 依赖：REQ-IX-FR-005。
- 候选强制组件：DeterministicPolicyEngine。
- ASR 理由：决定 AI 与策略的优先级关系，自动批准只能在基础策略允许时成立，是安全链的结构约束。
- 备注：新 INV-005 为幂等；旧 INV-005 的“不得降低审核级别”语义迁至本条。

### 5.3 安全需求

#### REQ-IX-SEC-001 隔离模型权限

- 类型：`security`（安全） · 优先级：Must · 状态：conceptual
- 需求：模型适配器应运行在最小权限边界内，仅能读取白名单证据字段并返回结构化建议，不得访问安装会话、特权服务或文件系统原始 APK。
- 验收标准（`static + negative`）：权限/依赖静态检查通过；适配器无法读取 APK 原始字节（接口级断言）；攻击指令样例后端调用 0。
- 来源：V0.2 INV-001。
- 依赖：无。
- 候选强制组件：AIRiskAdvisor。

#### REQ-IX-SEC-002 保留硬性禁止规则

- 类型：`security`（安全） · 优先级：Must · 状态：conceptual
- 需求：既有硬性禁止规则应由确定性代码在 AI 前后各执行一次，AI 建议和人工确认不得改变其结果。
- 验收标准（`negative`）：AI LOW + 人工确认下被禁止 APK 仍 BLOCK；AI 前后规则结果一致；后端 0。
- 来源：V0.2 INV-002。
- 依赖：无。
- 候选强制组件：DeterministicPolicyEngine。

#### REQ-IX-SEC-003 审批记录防篡改与失效

- 类型：`security`（安全） · 优先级：Must · 状态：conceptual
- 需求：审批记录应防篡改；绑定字段与 INV-003 一致，任一字段缺失或对象变化时立即失效，不得生成有效批准。
- 验收标准（`tamper + negative`）：修改存储中的绑定位后复验失败；旧批准无法用于新对象；审计可检测记录篡改（DATA-001）。
- 来源：V0.2 INV-003。
- 依赖：REQ-IX-FR-002。
- 候选强制组件：ApprovalStateStore。

#### REQ-IX-SEC-004 控制外发数据

- 类型：`security`（安全） · 优先级：Must · 状态：conceptual
- 需求：外发数据应遵守同意与联网开关，仅发送白名单元数据；不得发送 APK 二进制、设备标识、账号、token 或日志中的凭据。
- 验收标准（`capture + log scan`）：拒绝/断网时外发 0；请求体字段与白名单完全一致；日志扫描无 token/设备标识；抓包无 APK 字节。
- 来源：V0.2 §9 + 隐私风险。
- 依赖：REQ-IX-FR-002, REQ-IX-FR-011。
- 候选强制组件：NetworkGate。

#### REQ-IX-SEC-005 处理不可信文本

- 类型：`security`（安全） · 优先级：Must · 状态：conceptual
- 需求：APK 标签、来源字符串和其他包内文本在进入模型前应作为不可信数据处理：剥离 C0/C1 控制字符、NFKC 归一化、JSON 转义、单字段 ≤4KB、总输入 ≤32KB，并中和指令样式文本。
- 验收标准（`adversarial corpus`）：≥20 条注入样本（Latin/CJK/Unicode/JSON/超长）自动批准 0 次；净化后模型输入不含原恶意串；超长被截断并记录；系统无崩溃。
- 来源：V0.2 §3.1 Prompt Injection。
- 依赖：REQ-IX-FR-002。
- 候选强制组件：EvidenceSanitizer。
- ASR 理由：在外部模型前引入净化管线，决定证据流与接口契约，是注入防线的结构点。

### 5.4 可靠性需求

#### REQ-IX-REL-001 失败安全降级

- 类型：`robustness`（可靠性） · 优先级：Must · 状态：conceptual
- 需求：模型超时、断网、限流、服务错误及无效输出应降级为 REQUIRE_REVIEW；硬性禁止保持 BLOCK；不得静默自动批准。
- 验收标准（`fault injection`）：五类故障注入自动执行 0；界面显示故障与下一步；硬性禁止仍 BLOCK。
- 来源：V0.2 INV-004。
- 依赖：REQ-IX-INV-004。
- 候选强制组件：ApprovalCoordinator。

#### REQ-IX-REL-002 持久化审批状态

- 类型：`robustness`（可靠性） · 优先级：Must · 状态：conceptual
- 需求：系统应在关键状态转换（收到请求、证据完成、建议完成、决定、批准、提交前）持久化请求、绑定信息与决定；无法可靠写入时停止自动提交。
- 验收标准（`restart + write-fault`）：写入故障注入后无自动安装；重启后读到最后一个成功状态与请求 ID；事务边界测试通过。
- 来源：V0.2 §5.1。
- 依赖：REQ-IX-FR-001。
- 候选强制组件：ApprovalStateStore。
- ASR 理由：引入持久状态存储与事务边界，决定恢复协议和 Room 数据模型。

#### REQ-IX-REL-003 处理中断恢复

- 类型：`robustness`（可靠性） · 优先级：Must · 状态：conceptual
- 需求：进程重启后，未提交请求应重新验证并重新审批；已提交但结果未知的请求应核对平台状态；无法核实时标记 RESULT_UNKNOWN 并禁止自动重放。
- 验收标准（`crash recovery`）：在模型等待、待审核、批准未提交、提交后终止四类场景恢复：不复用过期批准、不重复提交、未知可识别。
- 来源：V0.2 §3.2。
- 依赖：REQ-IX-REL-002, REQ-IX-SEC-003。
- 候选强制组件：ApprovalStateStore。
- ASR 理由：新增崩溃恢复协议、幂等键与状态对账，影响存储、网关和 UI。

#### REQ-IX-REL-004 防止重复提交

- 类型：`robustness`（可靠性） · 优先级：Must · 状态：conceptual
- 需求：系统应对同一安装操作 ID 的重复事件、连续点击、并发回调和恢复重放执行去重，保证最多一次自动提交；用户主动重试只新增 attempt ID，不新增提交权，且总调用受 COST-001 约束。
- 验收标准（`concurrency`）：同一 operation ID 并发 20 次提交 ≤1；用户重试产生新 attempt ID、同 operation ID；第 3 次调用被阻止；去重窗口内无重复执行。
- 来源：V0.2 §6 阶段六。
- 依赖：REQ-IX-REL-002。
- 候选强制组件：ApprovalStateStore。

### 5.5 性能与成本

#### REQ-IX-PER-001 限制等待时间

- 类型：`performance`（性能） · 优先级：Should · 状态：conceptual
- 需求：AI 审批的单次尝试预算为 15 秒：从请求进入 AWAITING_AI（证据完成且已同意）起，到决定写入持久状态止；单次模型调用超时 7 秒，不做自动重试；用户主动发起的第二次尝试单独计时。预算不含人工审核、排队与用户操作时间。
- 验收标准（`timeout stub`）：超时桩下 7 秒调用超时后 1 秒内出现 REQUIRE_REVIEW、15 秒内决定落库；调用计数为 1（无自动重试）；第二次用户触发单独计时；等待期间取消与页面交互可响应；无 ANR/主线程阻塞。
- 来源：V0.2 §6 Cost/Performance。
- 依赖：REQ-IX-REL-001。
- 候选强制组件：AIRiskAdvisor。

#### REQ-IX-COST-001 控制模型调用

- 类型：`cost`（成本） · 优先级：Should · 状态：conceptual
- 需求：同一安装操作最多 2 次模型调用：第 1 次由系统自动发起，第 2 次仅由用户主动触发；不做自动重试。单次调用输入+输出 ≤2000 tokens（至少 95% 调用），硬上限 4000；超限或超次数按失败处理并转 REQUIRE_REVIEW。
- 验收标准（`counter`）：成功请求调用数 1；超时不自动重试；用户可触发第 2 次；第 3 次被阻止并提示人工；token 统计与阈值报告齐全；超限转人工。
- 来源：V0.2 §6 Cost/Performance。
- 依赖：REQ-IX-FR-003。
- 候选强制组件：AIRiskAdvisor。

### 5.6 质量属性

#### REQ-IX-MOD-001 替换模型实现

- 类型：`quality`（质量） · subtype: `modifiability` · 优先级：Should · 状态：conceptual
- 需求：模型供应商与调用配置应通过统一适配接口封装；策略与 UI 不得依赖供应商响应细节。
- 验收标准（`adapter swap`）：固定响应适配器替换在线模型后，策略与审批测试无需修改业务规则即可通过；适配器契约测试通过。
- 来源：V0.2 §6 Modifiability。
- 依赖：REQ-IX-FR-003, REQ-IX-FR-004。
- 候选强制组件：AIRiskAdvisor。
- ASR 理由：定义外部依赖边界与测试替身，影响模块接口和依赖注入。

#### REQ-IX-COMP-001 保留既有约束

- 类型：`quality`（质量） · subtype: `compatibility` · 优先级：Must · 状态：conceptual
- 需求：AI 扩展应复用既有 APK 分析与安装能力，保持基线中既有策略、权限检查、通知、历史及两套 UI 的受影响行为可回归。
- 验收标准（`baseline regression`）：记录基线提交或归档哈希；列出的回归场景（安装/更新/降级/签名不符/黑名单）全部通过；未支持路径不显示“受 AI 审批保护”。
- 来源：V0.2 §2.1；§3.4。
- 依赖：无。
- 候选强制组件：Baseline regression suite。

#### REQ-IX-AIQ-001 评估建议质量

- 类型：`quality`（质量） · subtype: `ai_quality` · 优先级：Should · 状态：conceptual
- 需求：小组应建立人工标注的正常、异常、缺失证据和恶意文本用例集，人工评估理由是否真实、相关且不过度推断，并与结构有效性分开报告；例：APK 声明 READ_CONTACTS 可支持“申请读取联系人权限”，不能支持“已上传联系人”。
- 验收标准（`labeled evaluation`）：≥20 例、每例 3 次；结构有效率 ≥95%（自动）；人工有据率 ≥90%、相关性 ≥90%；报告含原始输出/分母/评分人；结果不得用于自动授权。
- 来源：V0.2 §9 AI 输出质量。
- 依赖：REQ-IX-FR-004。
- 候选强制组件：Evaluation harness。

#### REQ-IX-QUAL-001 建议可解释

- 类型：`quality`（质量） · subtype: `explainability` · 优先级：Should · 状态：conceptual
- 需求：每条被接受的 AI 理由必须引用至少一个存在的证据字段；自动检查只验证引用存在性与字段名合法性，不判断语义真实性；无引用或伪造引用判为 INVALID。
- 验收标准（`fixture + negative`）：标注集上 100% 被接受理由引用存在字段；0 条伪造引用；自动测试不依赖人工语义判断；语义支持由 AIQ-001 单独评估。
- 来源：V0.2 §9 AI 输出质量。
- 依赖：REQ-IX-FR-004。
- 候选强制组件：AIOutputValidator。

### 5.7 验证、治理与范围

#### REQ-IX-VER-001 离线与在线验证

- 类型：`governance`（治理） · subtype: `verification_process` · 优先级：Must · 状态：conceptual
- 需求：每条 Must 需求应关联测试或可执行检查；每条 invariant 至少一项负向测试；应分别保留固定响应测试与真实模型集成证据。
- 验收标准（`trace audit`）：需求→验证映射无空缺；移除保护后对应负向测试失败；真实模型记录环境、模型与 Prompt 版本。
- 来源：V0.2 §6 阶段六。
- 依赖：无。
- 候选强制组件：Test suite。

#### REQ-IX-GOV-001 需求追踪与人工评审

- 类型：`governance`（治理） · subtype: `traceability` · 优先级：Must · 状态：conceptual
- 需求：需求应保留唯一 ID、来源、优先级、验收标准、依赖与状态；依赖图无环；需求、架构、测试变更可追踪并经小组评审。
- 验收标准（`static check`）：YAML 可解析、ID 唯一、无悬空依赖、无环；评审记录可关联版本与理由。
- 来源：V0.2 §6 阶段二。
- 依赖：无。
- 候选强制组件：requirements registry。

#### REQ-IX-GOV-002 Coding Agent 治理

- 类型：`governance`（治理） · subtype: `agent_governance` · 优先级：Must · 状态：conceptual
- 需求：小组应保存 Plan、Build、Verify 活动与人工审查记录；代码生成前审批计划，安全关键改动关联人工批准与 ADR。
- 验收标准（`process audit`）：抽查 AI 组件改动与授权改动各一次，可找到计划批准、生成记录、验证结果、人工审查。
- 来源：V0.2 §6 阶段四。
- 依赖：REQ-IX-GOV-001。
- 候选强制组件：Process records。

#### REQ-IX-SCOPE-001 v1 范围

- 类型：`governance`（治理） · subtype: `scope` · 优先级：Must · 状态：conceptual
- 需求：v1 仅覆盖 InstallerActivity 发起、InstallMode.AutoDialog、Authorizer.None（系统 PackageInstaller）演示后端的单 APK 安装；其他应用发起的 session（含 ActionHandler 自动批准）、模块、批量/分包、卸载/解包/网络 URL、Root/Shizuku 全覆盖、恶意软件检测、多 Agent、云平台均不在本版。
- 验收标准（`scope inventory`）：入口清单评审通过；范围外路径 AI 调用 0 次且行为不变；session 旁路写入威胁模型与 future work；非目标在文档中列出。
- 来源：V0.2 §2 + 组长边界。
- 依赖：无。
- 候选强制组件：Scope inventory。
- 备注：session 旁路为已知绕过，不在 v1 修复；不得宣称该路径受 AI 审批保护。

### 5.8 架构与数据约束

#### REQ-IX-ARCH-001 单一授权收口

- 类型：`architectural_constraint`（架构约束） · 优先级：Must · 状态：conceptual
- 需求：对 SCOPE-001 指定的单个本地 APK、InstallMode.AutoDialog、Authorizer.None 路径，每次安装提交前必须经 ApprovalCoordinator → InstallationGateway 唯一收口；该路径的安装后端接口不得被 AI、UI 或策略直接调用。范围外路径不要求收口，但不得调用 AI 组件。
- 验收标准（`static + dynamic spy`）：范围内正向测试经 Gateway；范围外路径 AI 调用 0；静态检查仅约束范围内后端注入；动态 spy 绕过 Coordinator 的调用为 0；架构评审记录收口图。
- 来源：V0.2 §4.2 + 基线架构。
- 依赖：REQ-IX-FR-007。
- 候选强制组件：InstallationGateway。
- ASR 理由：定义组件边界与调用契约，影响所有安装入口和依赖注入结构。

#### REQ-IX-ARCH-002 双 UI 一致性

- 类型：`architectural_constraint`（架构约束） · subtype: `ui_consistency` · 优先级：Should · 状态：conceptual
- 需求：新增的审核、同意与审计界面应同时适配 Material 3 与 Miuix 两套 UI；两套均能编译并完成同一流程，或由维护者批准并记录豁免。
- 验收标准（`build + UI smoke`）：两套 UI 的截图/测试各一份；编译通过；豁免需链接评审记录。
- 来源：AGENTS.md + 基线双 UI。
- 依赖：REQ-IX-FR-006。
- 候选强制组件：UI layer。

#### REQ-IX-DATA-001 审计数据完整性

- 类型：`data_constraint`（数据约束） · subtype: `audit_integrity` · 优先级：Must · 状态：conceptual
- 需求：审计记录应 append-only、带哈希链、可导出且仅本机保存；保留期限与删除入口在 OI-06 冻结前不得自动删除。
- 验收标准（`tamper test`）：篡改任一条记录可被检测；导出包含全部字段且可重放决策；无凭据；删除需显式操作并留痕。
- 来源：V0.2 §3.3 + 课程 data_constraint。
- 依赖：REQ-IX-FR-009。
- 候选强制组件：DecisionAuditRecorder。

## 6. 决策表、不变量与状态机

### 6.1 决策表（YAML `decision_rules`，按行序匹配，先命中生效）

| # | 规则 ID | 证据状态 | 建议状态 | 策略 | 人工 | 决策 | 处置 | 原因码 |
|---|---|---|---|---|---|---|---|---|
| 1 | `DR-01` | 任意 | 任意 | HARD_BLOCK | 任意 | `BLOCK` | `BLOCKED` | `HARD_BLOCK` |
| 2 | `DR-10` | 任意 | 任意 | 非 HARD_BLOCK | 批准后复验失败 | `REQUIRE_REVIEW` | `INVALIDATED` | `APPROVAL_INVALIDATED` |
| 3 | `DR-03` | UNREADABLE 或 KEY_UNKNOWN | 任意 | 非 HARD_BLOCK | 任意 | `REQUIRE_REVIEW` | `RECOLLECT` | `EVIDENCE_INCOMPLETE` |
| 4 | `DR-02` | 任意 | UNAVAILABLE（CONSENT_DECLINED） | 非 HARD_BLOCK | 未取得 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | `CONSENT_DECLINED` |
| 5 | `DR-04` | CONTRADICTORY | 任意 | 非 HARD_BLOCK | 未取得 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | `EVIDENCE_CONTRADICTORY` |
| 6 | `DR-05` | COMPLETE | VALID_LOW | AUTO_RULES_MET | 未取得 | `ALLOW_AUTO` | `EXECUTE_ONCE` | `AUTO_APPROVED` |
| 7 | `DR-06` | COMPLETE | VALID_LOW | AUTO_RULES_NOT_MET | 未取得 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | `AUTO_RULES_NOT_MET` |
| 8 | `DR-07` | COMPLETE | VALID_MEDIUM / VALID_HIGH / VALID_UNKNOWN | 非 HARD_BLOCK | 未取得 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | `RISK_NOT_LOW` |
| 9 | `DR-08` | COMPLETE | INVALID / UNAVAILABLE | 非 HARD_BLOCK | 未取得 | `REQUIRE_REVIEW` | `AWAIT_HUMAN` | `ADVICE_UNUSABLE` |
| 10 | `DR-09` | 任意 | 任意 | 非 HARD_BLOCK | 有效批准且复验通过 | `REQUIRE_REVIEW` | `EXECUTE_ONCE` | `APPROVED_AFTER_REVIEW` |

决策是策略结论（`ALLOW_AUTO | REQUIRE_REVIEW | BLOCK`），处置是执行动作（`EXECUTE_ONCE | AWAIT_HUMAN | BLOCKED | RECOLLECT | INVALIDATED`）。用户批准人工审核后，决策仍为 `REQUIRE_REVIEW`，只有处置变为 `EXECUTE_ONCE`。AI 关闭时不进入本表，按 `FR-012` 走基线并审计 `AI_DISABLED`。

按行序匹配，优先级固定为：硬性禁止 → 已有批准复验失败 → 绑定证据缺失 → 拒绝外发 → 常规规则；因此“拒绝外发 + 证据不可读”走 `RECOLLECT`，“复验失败 + 证据不可读”走 `INVALIDATED`。提交前复验若因 APK 不可读或绑定字段缺失而无法完成，同样视为复验失败（`DR-10`），先置 `INVALIDATED` 再重新取证。规则 1 优先于一切；`AUTO_RULES_MET` 至少包含签名检查、黑名单、证据完整性、目标用户、安装模式与后端匹配，且基础策略判定为 `ALLOW_AUTO`（`INV-007`）；未经评审的自动条件默认 `AUTO_RULES_NOT_MET`，来源未知时同样视为 `AUTO_RULES_NOT_MET`。`UNREADABLE` 或绑定字段缺失（`KEY_UNKNOWN`）时处置为 `RECOLLECT`，确认按钮不得提交；来源未知但 APK 字节、哈希与目标用户可核验时证据为 `COMPLETE`，可走人工审核。

### 6.2 不变量

| 不变量 | 标题 | 必须覆盖的负向场景 | 强制组件 |
|---|---|---|---|
| REQ-IX-INV-001 | AI 无直接特权执行能力 | 模型输出 install/approve 指令 | ApprovalCoordinator |
| REQ-IX-INV-002 | 建议不覆盖确定性禁止 | LOW 建议 + 人工确认 vs 黑名单 | DeterministicPolicyEngine |
| REQ-IX-INV-003 | 批准绑定安装对象 | APK/用户/配置/策略变化与 TOCTOU | ApprovalStateStore |
| REQ-IX-INV-004 | 模型故障不静默批准 | 超时、无效 JSON、服务失败 | ApprovalCoordinator |
| REQ-IX-INV-005 | 重复事件最多一次提交 | 并发点击、重复回调、恢复重放 | ApprovalStateStore |
| REQ-IX-INV-006 | 策略判定独立于模型 | 空适配器/断网下策略仍可判定 | DeterministicPolicyEngine |
| REQ-IX-INV-007 | AI 不降低确定性限制 | 基础策略 REVIEW/BLOCK 下 AI LOW + 人工批准仍不得自动批准 | DeterministicPolicyEngine |

### 6.3 状态机（建议，ADD 可细化）

`RECEIVED → ANALYZING → AWAITING_AI → AWAITING_REVIEW / BLOCKED / RECOLLECT → EXECUTING → SUCCEEDED / FAILED`；另含 `CANCELLED`、`INVALIDATED`、`RESULT_UNKNOWN`。复验失败先置 `INVALIDATED`，由恢复流程重新取证（`RECOLLECT`），不得沿用旧批准。审计状态与平台安装状态必须分开存储。

## 7. 验证与追踪

`validate_requirements.py` 对 YAML 做机器校验（ID 唯一、依赖无悬空、依赖无环、必填字段、类型/状态枚举、Must 必须有验收标准、`decision_rules` 字段与枚举合法），并验证 7 个跨场景首命中结果（含拒绝外发+不可读→`RECOLLECT`、复验失败+不可读→`INVALIDATED`、完整证据+拒绝外发→`AWAIT_HUMAN`）。下表为需求级追踪骨架；`planned` 表示尚未实现。

| 需求 | 验证方法 | 计划测试/证据 | 状态 |
|---|---|---|---|
| REQ-IX-FR-001 | integration + negative | planned: ApprovalRequestTest（证据：planned） | planned |
| REQ-IX-FR-002 | unit + fixture | planned: EvidenceCollectorTest（证据：planned） | planned |
| REQ-IX-FR-003 | integration (online) + offline fixed response | planned: AdvisorIntegrationTest（证据：planned） | planned |
| REQ-IX-FR-004 | unit negative | planned: OutputValidationTest（证据：planned） | planned |
| REQ-IX-FR-005 | decision-table + property | planned: PolicyDecisionTableTest（证据：planned） | planned |
| REQ-IX-FR-006 | UI + negative | planned: ReviewUiTest（证据：planned） | planned |
| REQ-IX-FR-007 | integration + negative | planned: ControlledInstallTest（证据：planned） | planned |
| REQ-IX-FR-008 | UI + negative | planned: ProgressCancelTest（证据：planned） | planned |
| REQ-IX-FR-009 | audit replay | planned: AuditRecordTest（证据：planned） | planned |
| REQ-IX-FR-010 | UI + export | planned: AuditQueryTest（证据：planned） | planned |
| REQ-IX-FR-011 | UI + counter | planned: ConsentGateTest（证据：planned） | planned |
| REQ-IX-FR-012 | config matrix + negative | planned: ToggleSemanticsTest（证据：planned） | planned |
| REQ-IX-INV-001 | static + dynamic negative | planned: AdvisorIsolationTest（证据：planned） | planned |
| REQ-IX-INV-002 | negative | planned: HardBlockPrecedenceTest（证据：planned） | planned |
| REQ-IX-INV-003 | negative + TOCTOU | planned: ApprovalBindingTest（证据：planned） | planned |
| REQ-IX-INV-004 | fault injection | planned: FailureFallbackTest（证据：planned） | planned |
| REQ-IX-INV-005 | concurrency + recovery | planned: IdempotencyTest（证据：planned） | planned |
| REQ-IX-INV-006 | static + unit | planned: PolicyWithoutModelTest（证据：planned） | planned |
| REQ-IX-INV-007 | decision-table negative | planned: NoDowngradeTest（证据：planned） | planned |
| REQ-IX-SEC-001 | static + negative | planned: AdapterPermissionTest（证据：planned） | planned |
| REQ-IX-SEC-002 | negative | planned: HardRuleRegressionTest（证据：planned） | planned |
| REQ-IX-SEC-003 | tamper + negative | planned: ApprovalTamperTest（证据：planned） | planned |
| REQ-IX-SEC-004 | capture + log scan | planned: OutboundWhitelistTest（证据：planned） | planned |
| REQ-IX-SEC-005 | adversarial corpus | planned: SanitizerInjectionTest（证据：planned） | planned |
| REQ-IX-REL-001 | fault injection | planned: FailureModeTest（证据：planned） | planned |
| REQ-IX-REL-002 | restart + write-fault | planned: StatePersistenceTest（证据：planned） | planned |
| REQ-IX-REL-003 | crash recovery | planned: RecoveryTest（证据：planned） | planned |
| REQ-IX-REL-004 | concurrency | planned: DedupTest（证据：planned） | planned |
| REQ-IX-PER-001 | timeout stub | planned: LatencyBudgetTest（证据：planned） | planned |
| REQ-IX-COST-001 | counter | planned: CallBudgetTest（证据：planned） | planned |
| REQ-IX-MOD-001 | adapter swap | planned: AdapterContractTest（证据：planned） | planned |
| REQ-IX-COMP-001 | baseline regression | planned: BaselineRegressionTest（证据：planned） | planned |
| REQ-IX-AIQ-001 | labeled evaluation | planned: AIQualityEvaluation（证据：planned） | planned |
| REQ-IX-QUAL-001 | fixture + negative | planned: ExplanationGroundingTest（证据：planned） | planned |
| REQ-IX-VER-001 | trace audit | planned: requirements trace check（证据：planned） | planned |
| REQ-IX-GOV-001 | static check | validate_requirements.py（证据：planned） | planned |
| REQ-IX-GOV-002 | process audit | planned: review log（证据：planned） | planned |
| REQ-IX-SCOPE-001 | scope inventory | planned: entry-point audit（证据：planned） | planned |
| REQ-IX-ARCH-001 | static + dynamic spy | planned: ChokePointTest（证据：planned） | planned |
| REQ-IX-ARCH-002 | build + UI smoke | planned: DualUiSmokeTest（证据：planned） | planned |
| REQ-IX-DATA-001 | tamper test | planned: AuditIntegrityTest（证据：planned） | planned |

## 8. MoSCoW

- Must：34 条，覆盖可运行审批闭环、授权边界、对象一致性、异常降级、状态审计与范围约束。
- Should：7 条，覆盖审计查询、性能/成本、模型可替换性、AI 质量与双 UI。
- Could：更丰富的风险解释、审计导出格式扩展、更多安装模式。
- Won’t：批量/分包、模块、多 Agent、恶意软件检测平台、云管理端。

时间不足时先删 Should 的展示项，不删 `INV-001`～`INV-007` 与 `REL-002`～`REL-004`；调整 Must 必须重新评审范围与验收。

## 9. 待小组确认事项

| 编号 | 事项 | v0.2.1 处理 |
|---|---|---|
| OI-01 | 官方 Project Requirement Sheet / D1 模板 | 仍待核对；本版按 RE 课件 registry 字段组织 |
| OI-02 | 固定源码基线、设备、演示后端 | 默认：外层仓库提交哈希 + google_apis API 37 模拟器 + Authorizer.None |
| OI-03 | 云端/本地模型、供应商、凭据 | 接口中立；默认云端 + 前置同意；供应商待定 |
| OI-04 | 自动批准规则与人工审核范围 | 默认保守：`AUTO_RULES_NOT_MET`，全部走人工，待评审后收紧 |
| OI-05 | 超时、调用次数、质量指标 | 默认单次尝试 15s / 单次调用 7s / 无自动重试 / 每操作 1 自动 + 1 用户触发 / 20 例×3；待组会确认 |
| OI-06 | 审计保留期限、删除入口 | 默认本机保存、不自动删除；保留期待定 |
| OI-07 | 关闭 AI / 拒绝外发的行为 | 已决议：关闭 AI = `FR-012` 基线 + `AI_DISABLED`；拒绝同意 = `FR-011` 零调用 + `REQUIRE_REVIEW` |
| OI-08 | 两份 registry 并存 | 已决议：本版为唯一内容基线；旧 `docs/req-registry.yaml` 归档为格式样例 |

## 10. 合并与 ID 映射

旧 ID 与新版 ID 不是简单同名映射：下表逐条给出 **旧文件及版本 / 旧 ID / 新 ID / 处理方式**。引用旧编号的 ADR、测试与评审记录应按本表改写。

### 10.1 旧 `docs/req-registry.yaml`（22 条）

| 旧文件及版本 | 旧 ID | 新 ID | 处理方式 |
|---|---|---|---|
| `docs/req-registry.yaml` v1 | `REQ-IX-FR-001` | REQ-IX-FR-002 | 迁移：收集证据语义；旧编号由新“接收审批请求”复用 |
| `docs/req-registry.yaml` v1 | `REQ-IX-FR-002` | REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-FR-005 | 拆分：模型调用、输出验证、确定性判定 |
| `docs/req-registry.yaml` v1 | `REQ-IX-FR-003` | REQ-IX-FR-005 | 迁移：PM-001 判定并入 FR-005 |
| `docs/req-registry.yaml` v1 | `REQ-IX-FR-004` | REQ-IX-FR-006, REQ-IX-ARCH-002 | 拆分：人工审核 UI 与双 UI 约束 |
| `docs/req-registry.yaml` v1 | `REQ-IX-FR-005` | REQ-IX-REL-002, REQ-IX-FR-009, REQ-IX-REL-003 | 拆分：持久化、审计、恢复 |
| `docs/req-registry.yaml` v1 | `REQ-IX-INV-001` | REQ-IX-INV-001 | 保留 |
| `docs/req-registry.yaml` v1 | `REQ-IX-INV-002` | REQ-IX-INV-002, REQ-IX-SEC-002 | 保留，拆出硬规则执行 |
| `docs/req-registry.yaml` v1 | `REQ-IX-INV-003` | REQ-IX-INV-003, REQ-IX-SEC-003 | 保留，拆出防篡改 |
| `docs/req-registry.yaml` v1 | `REQ-IX-INV-004` | REQ-IX-INV-004, REQ-IX-REL-001 | 保留，拆出失败降级 |
| `docs/req-registry.yaml` v1 | `REQ-IX-INV-005` | REQ-IX-INV-007 | 语义迁移；新 INV-005 改为幂等（编号复用） |
| `docs/req-registry.yaml` v1 | `REQ-IX-SEC-001` | REQ-IX-SEC-005 | 迁移：净化规格 |
| `docs/req-registry.yaml` v1 | `REQ-IX-SEC-002` | REQ-IX-SEC-001, REQ-IX-SEC-004 | 拆分：权限隔离与外发白名单 |
| `docs/req-registry.yaml` v1 | `REQ-IX-SEC-003` | REQ-IX-SEC-004 | 迁移：联网开关零请求 |
| `docs/req-registry.yaml` v1 | `REQ-IX-GOV-001` | REQ-IX-FR-009, REQ-IX-DATA-001 | 拆分：审计字段与完整性 |
| `docs/req-registry.yaml` v1 | `REQ-IX-GOV-002` | REQ-IX-GOV-002 | 保留 |
| `docs/req-registry.yaml` v1 | `REQ-IX-QUAL-001` | REQ-IX-QUAL-001, REQ-IX-AIQ-001 | 拆分：自动引用检查与人工事实支持 |
| `docs/req-registry.yaml` v1 | `REQ-IX-ROBUST-001` | REQ-IX-FR-004, REQ-IX-REL-001 | 拆分：输出验证与失败降级 |
| `docs/req-registry.yaml` v1 | `REQ-IX-PERF-001` | REQ-IX-PER-001 | 阈值改写：p95 5s → 单次尝试 15s / 单次调用 7s |
| `docs/req-registry.yaml` v1 | `REQ-IX-COST-001` | REQ-IX-COST-001 | 合并：调用次数 + token 上限 |
| `docs/req-registry.yaml` v1 | `REQ-IX-ARCH-001` | REQ-IX-ARCH-001 | 限缩到 SCOPE-001 路径 |
| `docs/req-registry.yaml` v1 | `REQ-IX-DATA-001` | REQ-IX-DATA-001 | 保留 |
| `docs/req-registry.yaml` v1 | `REQ-IX-SCOPE-001` | REQ-IX-SCOPE-001 | 改写：AutoDialog + Authorizer.None + session non-goal |

### 10.2 旧 `requirements/requirements.yaml` V0.1（27 条）

| 旧文件及版本 | 旧 ID | 新 ID | 处理方式 |
|---|---|---|---|
| `requirements/requirements.yaml` V0.1 | `FR-001` | REQ-IX-FR-001 | 保留；新增 operation ID |
| `requirements/requirements.yaml` V0.1 | `FR-002` | REQ-IX-FR-002 | 保留 |
| `requirements/requirements.yaml` V0.1 | `FR-003` | REQ-IX-FR-003 | 保留；加 attempt 语义 |
| `requirements/requirements.yaml` V0.1 | `FR-004` | REQ-IX-FR-004 | 保留 |
| `requirements/requirements.yaml` V0.1 | `FR-005` | REQ-IX-FR-005 | 保留 |
| `requirements/requirements.yaml` V0.1 | `FR-006` | REQ-IX-FR-006 | 保留 |
| `requirements/requirements.yaml` V0.1 | `FR-007` | REQ-IX-FR-007 | 保留；绑定缺失禁止提交 |
| `requirements/requirements.yaml` V0.1 | `FR-008` | REQ-IX-FR-008 | 保留 |
| `requirements/requirements.yaml` V0.1 | `FR-009` | REQ-IX-FR-009 | 保留；记 operation/attempt ID |
| `requirements/requirements.yaml` V0.1 | `FR-010` | REQ-IX-FR-010 | 保留 |
| `requirements/requirements.yaml` V0.1 | `SEC-001` | REQ-IX-SEC-001 | 保留 |
| `requirements/requirements.yaml` V0.1 | `SEC-002` | REQ-IX-SEC-002 | 保留 |
| `requirements/requirements.yaml` V0.1 | `SEC-003` | REQ-IX-SEC-003, REQ-IX-INV-003 | 拆分：防篡改与绑定 |
| `requirements/requirements.yaml` V0.1 | `SEC-004` | REQ-IX-SEC-004, REQ-IX-FR-011 | 拆分：外发白名单与同意 |
| `requirements/requirements.yaml` V0.1 | `SEC-005` | REQ-IX-SEC-005 | 保留 |
| `requirements/requirements.yaml` V0.1 | `REL-001` | REQ-IX-REL-001, REQ-IX-INV-004 | 拆分：降级与不变量 |
| `requirements/requirements.yaml` V0.1 | `REL-002` | REQ-IX-REL-002 | 保留 |
| `requirements/requirements.yaml` V0.1 | `REL-003` | REQ-IX-REL-003 | 保留 |
| `requirements/requirements.yaml` V0.1 | `REL-004` | REQ-IX-REL-004, REQ-IX-INV-005 | 拆分：去重与幂等 |
| `requirements/requirements.yaml` V0.1 | `PER-001` | REQ-IX-PER-001 | 改写：15s/7s、无自动重试 |
| `requirements/requirements.yaml` V0.1 | `COST-001` | REQ-IX-COST-001 | 改写：2 次调用 + token 上限 |
| `requirements/requirements.yaml` V0.1 | `MOD-001` | REQ-IX-MOD-001 | 保留 |
| `requirements/requirements.yaml` V0.1 | `COMP-001` | REQ-IX-COMP-001 | 保留 |
| `requirements/requirements.yaml` V0.1 | `VER-001` | REQ-IX-VER-001 | 保留 |
| `requirements/requirements.yaml` V0.1 | `AIQ-001` | REQ-IX-AIQ-001, REQ-IX-QUAL-001 | 拆分：人工事实支持与自动引用 |
| `requirements/requirements.yaml` V0.1 | `GOV-001` | REQ-IX-GOV-001 | 保留 |
| `requirements/requirements.yaml` V0.1 | `GOV-002` | REQ-IX-GOV-002 | 保留 |

### 10.3 V0.1 正文 INV 表（5 条）

| 旧文件及版本 | 旧 ID | 新 ID | 处理方式 |
|---|---|---|---|
| V0.1 正文 INV 表 | `INV-001` | REQ-IX-INV-001 | 升级为一等需求 |
| V0.1 正文 INV 表 | `INV-002` | REQ-IX-INV-002 | 升级为一等需求 |
| V0.1 正文 INV 表 | `INV-003` | REQ-IX-INV-003 | 升级为一等需求 |
| V0.1 正文 INV 表 | `INV-004` | REQ-IX-INV-004 | 升级为一等需求 |
| V0.1 正文 INV 表 | `INV-005` | REQ-IX-INV-007 | 语义迁移；新 INV-005 为幂等 |

### 10.4 本版新增的关键决策

| 编号 | 决策 | 理由 |
|---|---|---|
| D-01 | v1 路径 = AutoDialog + Authorizer.None | 唯一有 UI 且能自动提交；模拟器可实测 |
| D-02 | session 旁路为 non-goal，不修复、不宣称受保护 | 组长边界；写入威胁模型，避免“假安全” |
| D-03 | AI 关闭 = 零调用 + 既有检查 + 审计 AI_DISABLED | 防止开关成为绕过 |
| D-04 | 拒绝外发 = 零调用 + REQUIRE_REVIEW，不等于关闭 AI | 隐私边界；与 D-03 分开验收 |
| D-05 | 决策与处置分列；硬性禁止永远第一优先 | 消除 ALLOW_AUTO 与 EXECUTE_ONCE 混用 |
| D-06 | 单次尝试 15s、单次调用 7s、无自动重试；每操作 1 自动 + 1 用户触发 | 消除 15s/16s/2 次调用的算术冲突 |
| D-07 | 新增 INV-006：策略独立于模型 | deterministic cage 的核心 |
| D-08 | 双 UI 为 Should，可记录豁免 | 尊重仓库规则，同时避免阻塞闭环 |
| D-09 | 证据不可读/绑定缺失 = RECOLLECT，确认按钮禁用 | 无哈希/用户即无“复验通过”的定义 |
| D-10 | ARCH-001 只约束 SCOPE-001 路径 | 避免把批量/外部 session 拉进 v1 |
| D-11 | 决策表入 YAML decision_rules，md 由数据生成 | 单一机器可读源，防止两份漂移 |
| D-12 | QUAL-001 自动查引用存在性；AIQ-001 人工评真实性/相关性 | 同一批输出可分别合格/不合格 |
| D-13 | 决策规则按固定优先级匹配 | 避免“任意”条件跨行抢先，导致 RECOLLECT 被 AWAIT_HUMAN 覆盖 |

## 11. 与基线代码的接口点（供 ADD 使用）

- **入口**：`InstallerActivity`（VIEW/INSTALL_PACKAGE/SEND）→ session → `ProcessInstallationUseCase` 执行前挂审批；安装分发在 `AppInstallerRepositoryImpl`，须收口到 `InstallationGateway`。
- **session 旁路**：`data/session/handler/ActionHandler.kt:756-765` 在 `autoApproveSession` 时直接 `handleConfirm(..., true)`。按 D-02 不修改，但必须在威胁模型与 SCOPE-001 中显式记录。
- **安装模式**：`InstallMode.AutoDialog` 在 `InstallerViewModel.kt:596` 500ms 后自动 `install()`；`Ignore` 只是结束任务，不是安装路径。
- **状态持久化**：`InstallerSessionRepositoryImpl.kt:40-65` 是 Compose 内存态；`REL-002` 要求提交前落 Room（基线已有 Room 3）。
- **网络边界**：`OkHttpNetworkResolver.kt:97` 用 `BooleanSetting.AllowInternetAccess` 拦截；`SEC-004` 必须复用该开关，不另造。
- **双 UI**：`ui/page/main`（Material 3）与 `ui/page/miuix`（Miuix）；`AGENTS.md` 要求两边同步。
- **测试基线**：`app/src/test` 下 50 个测试文件，依赖 `kotlin-test-junit` 与 `coroutines-test`；新测试沿用同一目录结构。
- **演示环境**：google_apis API 37 模拟器；`Authorizer.None` 下系统 `PackageInstaller` 已完成一次真实更新（`lastUpdateTime` 变化），Shizuku 无 binder。

---

本稿由 V0.1 + 旧 registry 合并生成，并按 2026-11-14 评审意见修订为 rev.3；数值目标与范围决议为提案，待组会确认。
