# YukiHRT

Android 端的 HRT 血药浓度估算与记录工具。根据体重与给药记录推算雌二醇（E2）浓度曲线，并在长期用药后估算睾酮（T）抑制趋势；支持录入实测值校准个体模型，可选接入兼容 OpenAI 的模型 API 获取解读参考。

> **重要声明**
> 本应用输出的是基于公开药代/药效参数的**数学估算**，不是化验结果，也不构成任何用药、剂量或停药建议。曲线与参数仅用于记录辅助和自我观察。任何调整都应先与有资质的内分泌科医生沟通。
> 高剂量醋酸环丙孕酮（CPA）存在泌乳素升高、HDL-C 下降与脑膜瘤风险，请勿自行加量。

## 功能

- **给药记录**：单次录入与批量录入（支持一年以上的每日记录），区分口服与舌下含服。时间一律**点选**，不要求手打 `yyyy-MM-dd HH:mm`：单次给药用「此刻 / 1 小时前 / 3 小时前 + 快捷日期 + 日历 + 24 小时整点 + 5 分钟档」；批量补录用「近 7 / 30 / 90 天」区间快捷项加开始、结束日期与每日给药时刻，日历里晚于今天的日期不可选。
- **浓度曲线**：以当前时间为锚点，左半为历史估算浓度，右半为未来预测；可切换 ±12 / ±24 / ±36 / ±84 小时窗口。纵轴带单位标注，可在 E2（`pg/mL` ↔ `pmol/L`）、T（`ng/dL` ↔ `nmol/L`）间实时切换，只影响显示，不改动拟合结果。舌下 E2 按**两相**（0.8 h + 14 h，终末相承担约 70% AUC），口服 EV 单相，**CPA 按三相**：主相 4 h（69.5%）+ 浅室 36 h（24%）+ 深室 90 h（6.5%）。三相不是为了好看：Kuhnz 1993 实测 CPA 单次 t½ 54.0 ± 26 h（近两天），连续 3 个周期后延长到 78.6 ± 16 h（三天多），Vz 由 986 L 增至 1 304 L —— 这是脂肪 depot 造成的深室蓄积，说明书也写明「每日给药蓄积约 3 倍」。在线性叠加下，单次给药时深室只占 6.5%，表观终末 **53.7 h**；连续服药后深室的蓄积倍数 `1/(1−e^−kd·τ)` 远大于浅室，尾部改由深室接管，停药斜率自动延长到 **78.7 h**。所有结构都与 `CL/F = D/AUC` 严格自洽。**界面同时给出三个时间尺度，不要混用**：「峰值后降半」是从 Cmax 落到一半的实际时长（CPA 9.9 h，给药后约 13 h；舌下 1.5 h）；「单次终末 t½」是首剂后的尾部斜率（CPA 53.7 h，约 2.2 天）；「长期终末 t½」是稳态后的斜率（CPA 78.7 h，约 3.3 天）。
- **可折叠卡片**：四个页面的区块都改成点标题展开/收起，收起时保留一句摘要（体重、当前药物、最近记录、拟合层级、终末 t½ 等）。首屏只需要看曲线，补录历史、公式推导、文献来源这些偶尔才用的区块默认折起来，滚动距离比 1.0.9 少一半以上；展开状态会随旋转保存。
- **药物**：戊酸雌二醇（EV）口服 / 舌下；醋酸环丙孕酮（CPA）口服。
- **睾酮估算**：HRT 起点由**最早一条给药记录**自动确定（不再手动填写开始日期），累计满 90 天后进入抑制平台期。T 由 7 日平均 E2 暴露与 7 日平均 CPA **血药浓度**共同驱动——CPA 浓度先折算成等效日剂量，再经饱和函数压低 T，所以稳态下与「按剂量」完全等价，但停药/漏服时抑睾效应会随 CPA 的终末相（单次 54 h、长期 79 h）逐渐消退，而不是当天跳回基线。T 曲线始终使用 `ng/dL` / `nmol/L` 绝对单位。
- **T 个体 PD 拟合**：**不要求未治疗基线**。填了基线就拟合 Emax；没填就把基线本身作为待估参数，与 Emax 一起用治疗中的 T 实测点联合估计（带 550 ng/dL 群体先验）。只有 1 个点时退化为「基线锚定」（Emax 取先验），第 2 个点起基线 + Emax 同时估计。界面会标明当前基线是化验值还是估计值。
- **实测校准**：录入化验值与采血时间后立即开始拟合——第一个点就校准整体暴露；点数只决定能放开几个参数（2 点含峰后开始估消除、3 点覆盖峰前峰后开始估吸收），界面提示下一步建议，而不是设成使用门槛。药物·途径会自动切到实际有数据的那个；化验点不再要求关联的给药记录仍然存在。
- **性能**：给药记录预编译成「幅度 + ke/ka」内核，超过 20 个半衰期的剂量直接截断；参数、个体 PK 拟合与 T PD 拟合均带结果缓存。一年每日双药（730 条）时单次曲线计算约 40 ms，缓存命中后约 0 ms。
- **公式页**：所有公式、参数来源与文献链接集中展示。
- **AI 解读**：本地保存 API Key（Android Keystore AES-GCM 加密），发送前二次确认，Key 只用于 Authorization 头。请求默认**关闭思考模式**：先发 `enable_thinking` / `thinking` 开关，服务商不认（HTTP 400）时降级到 `reasoning_effort` / `reasoning`，再不认则不带该参数，并记住可用的写法。内置「测试连接」——只发一句固定短语、不发送任何记录，回显耗时与实际使用的关思考方式。发送内容是**全量历史摘要**（`AiPayload`）：连续相同方案合并成日期区间，逐条明细只留最近 21 天，全部化验点保留，另附累计用量与拟合诊断；可在界面点「查看摘要」核对实际发送的全文。

## 技术栈

- Kotlin + Jetpack Compose（Material 3），单 Activity + 四页底部导航
- `minSdk 26` / `targetSdk 35` / `compileSdk 35`，包名 `cn.hrtcurve.app`
- Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + JDK 17
- 数据全部保存在应用私有目录（SharedPreferences），`allowBackup=false`，不上传服务器

## 构建

本机工具链装在项目内 `.tools/`（不入版本库），无需改系统环境变量：

```bash
cd C:/Users/Yuki/WorkBuddy/HRT

# 调试包
JAVA_HOME="$PWD/.tools/jdk-17.0.21+9" \
ANDROID_HOME="$PWD/.tools/android-sdk" \
./.tools/gradle-8.9/bin/gradle assembleDebug --offline

# 发布包（需先准备 keystore.properties，见下）
JAVA_HOME="$PWD/.tools/jdk-17.0.21+9" \
ANDROID_HOME="$PWD/.tools/android-sdk" \
./.tools/gradle-8.9/bin/gradle assembleRelease --offline
```

`local.properties` 需包含：

```
sdk.dir=C:/Users/Yuki/WorkBuddy/HRT/.tools/android-sdk
```

## 签名

发布签名由根目录 `keystore.properties` 提供，`app/build.gradle.kts` 在文件存在时才创建 `signingConfigs.release`，缺失时仍可正常构建 debug 包。

```
storeFile=keystore/yukihrt-release.jks
storePassword=<本地保存>
keyAlias=yukihrt
keyPassword=<本地保存>
```

- 密钥库：`keystore/yukihrt-release.jks`（PKCS12，RSA 4096，有效期约 30 年）
- `keystore.properties`、`keystore/`、`*.jks` 已写入 `.gitignore`，**不会提交**
- 这两个文件必须一起备份；丢失后无法对已安装版本做覆盖升级
- 校验签名：

```bash
./.tools/android-sdk/build-tools/35.0.0/apksigner.bat verify --print-certs \
  dist/YukiHRT-1.0.0-release.apk
```

## 安装

- 产物：`dist/YukiHRT-<version>-release.apk`，`app/build/outputs/apk/release/app-release.apk`
- release 与 debug 使用不同证书，设备上如装过 debug 版需**先卸载**，否则提示签名冲突（会清空本地记录与已存 Key）
- 同一密钥库签名的后续版本可直接覆盖安装

## 测试

```bash
JAVA_HOME="$PWD/.tools/jdk-17.0.21+9" \
ANDROID_HOME="$PWD/.tools/android-sdk" \
./.tools/gradle-8.9/bin/gradle testDebugUnitTest --offline
```

`app/src/test/java/cn/hrtcurve/app/PkEngineTest.kt` 覆盖两相/三相处置与叠加、口服/舌下差异、蓄积、90 天平台期、CPA 剂量饱和与个体拟合边界，并把下面「模型校验」一节的锚点写成断言（`clearance_equalsDoseOverAuc_forEveryProfile`、`cpa_aucMatchesClearanceLowerBound`、`cpa_terminalHalfLifeProlongesAfterRepeatedDosing`、`cpa_peakToHalfStaysConsistentWithTerminalPhase`、`cpa_steadyStateMatchesClearanceDerivedAverage`、`sublingual_singleDoseAucMatchesReference`、`oralEv_aucIsAboutTwentyPercentAbovePublishedValue`、`stoppingCpa_keepsSuppressionThenRecoversGradually`）。

## 模型校验

每个参数都对着公开实测值核过，模型值与文献值的偏差写在测试里，避免以后悄悄漂移。

| 药物 · 途径 | 锚点 | 文献值 | 模型值 | 偏差 |
|---|---|---|---|---|
| EV 口服 1 mg | Cmax | 20.47 pg/mL（Zhang 2024，空腹参比） | 20.47 | 校准锚点 |
| EV 口服 1 mg | Tmax | 10 h | 10.00 h | 校准锚点 |
| EV 口服 1 mg | t½ | 14.48 h | 14.48 h | 校准锚点 |
| EV 口服 1 mg | AUC₀₋∞ | 576（几何均值 534） | 690 | **+20%（几何均值 +29%）** |
| EV 舌下 1 mg | Cmax | 144 pg/mL × 0.764（Doll 2021 摩尔当量） | 110.0 | 校准锚点 |
| EV 舌下 1 mg | AUC₀₋₈ | 约为口服 17β-E2 的 1.8 倍（Doll 2021） | 325 pg·h/mL | 量级一致 |
| EV 舌下 1 mg | AUC₀₋∞ | 无直接实测，由 Cmax + 0.8/14 h 两相外推 | 627 | 外推值 |
| EV 舌下 1 mg | 12 h 浓度 | ≥ 20 pg/mL（旧版单相 5.8） | 12.3 | 见下 |
| CPA 口服 50 mg | Cmax / Tmax | 140 ng/mL @ 约 3 h | 140.0 @ 3.00 h | 校准锚点 |
| CPA 口服 50 mg | 主相 t½ | 0–8 h 的 α 相 3.3–4.2 h（Frolich 1980） | 主相 4.0 h；3→8 h 割线 9.5 h | 更平，见下 |
| CPA 口服 50 mg | 24–120 h 斜率 | 43.9 ± 12.8 h（Siterone/Androcur SPC） | 44.7 h（浅室 36 h 与深室 90 h 的混合斜率） | −2% |
| CPA 口服 50 mg | 深室 t½（参数） | 78.6 ± 16.0 h（Kuhnz 1993，3 个周期后） | 90 h（稳态斜率与之对齐，见下） | 参数值偏高，斜率吻合 |
| CPA 口服 50 mg | **单次表观终末 t½** | 54.0 ± 26.0 h（Kuhnz 1993 单次） | **53.7 h**（96→168 h 斜率） | −0.6%（≈两天） |
| CPA 50 mg q24h 稳态 | **长期终末 t½** | 78.6 ± 16.0 h | **78.7 h**（稳态 168→336 h 斜率） | +0.1%（三天多） |
| CPA 口服 50 mg | 峰值后降半（曲线上能量出来） | — | 9.9 h（给药后约 13 h） | — |
| CPA 口服 50 mg | 6 / 8 / 24 / 48 / 72 h | 无逐点实测 | 118.3 / 99.9 / 45.0 / 28.8 / 19.9 | — |
| CPA 口服 50 mg | CL | 3.5 ± 1.5（SPC）；2.32 ± 0.38（综述） | 2.10 mL/min/kg（CL/F 10.0 L/h × F 0.88） | 落在区间下限 |
| CPA 口服 50 mg | AUC₀₋∞ | 3 450（BE 2×50 mg）；5 238（按 CL 下限反推） | 4 995 | 取清除率下限口径 |
| CPA 50 mg q24h 稳态 | 峰 / 谷 / 平均 | Frolich 1980 间隔采血 199–228 ng/mL（非 24 h 平均） | 285 / 152 / 208 | 仅作量级对照 |
| CPA 50 mg q24h 稳态 | 蓄积 | 说明书「约 ×3」 | 谷 ×3.38、峰 ×2.03 | 与「三倍之多」一致 |
| CPA 50 mg q24h 稳态 | 达稳态时间 | Kuhnz 1993：约第 16 天 | 约 4 个深室半衰期 ≈ 15 天 | 一致 |

**关于终末半衰期的三个数字。** 这三个数经常被混着说，实际上指的是不同窗口：

1. **9.9 h（峰值后降半）** —— 曲线上能量出来的那一段，从 3 h 达峰到 13 h 减半。它由主相决定，说明一次给药的实际作用时长远短于 54 h。
2. **53.7 h（单次表观终末，约两天）** —— 首剂后 96–168 h 的斜率。深室此时只占 6.5%，斜率落在浅室 36 h 与深室 90 h 之间，与 Kuhnz 单次 54 ± 26 h 一致。
3. **78.7 h（长期终末，三天多）** —— 连续给药后深室蓄积（q24 下深室 ×5.9、浅室 ×2.7），尾部由深室主导，稳态后停药斜率趋向此值；Kuhnz 实测 3 个周期后为 78.6 ± 16 h，Vz 986 → 1 304 L。

文献里那三个数字之所以能被同一组参数同时复现，是因为它们是**同一组参数在不同窗口的斜率**，不是三个不同的相：24→120 h 给 44.7（对 SPC 的 43.9）、96→168 h 给 53.7（对 Kuhnz 单次的 54.0）、稳态 168→336 h 给 78.7（对 Kuhnz 三周期后的 78.6）。

### 1.0.14：三相各自调到位

1.0.14 之前深室是「份额 8% + 78.6 h」，单次斜率 57.1 h 偏长、稳态斜率只有 61.4 h 偏短，两头都不对。把深室份额降到 6.5%、深室半衰期拉到 90 h，浅室从 43.9 h 收到 36 h（这样 24–120 h 的**混合**斜率正好落在 43.9 h），结果为：

| 指标 | 1.0.13 | **1.0.14** | 文献 |
|---|---|---|---|
| 单次 96→168 h 斜率 | 57.1 h | **53.7 h** | 54.0 ± 26.0（Kuhnz 单次） |
| 稳态 168→336 h 斜率 | 61.4 h | **78.7 h** | 78.6 ± 16.0（Kuhnz 3 周期） |
| 24–120 h 斜率 | 44.7 h | **44.7 h** | 43.9 ± 12.8（SPC） |
| 6 h 保留 / 峰值后降半 | 83% / 9.1 h | **85% / 9.9 h** | — |
| 谷蓄积 | ×3.67 | **×3.38** | 「约 ×3」 |

关键在蓄积倍数的不对称：q24 下深室的蓄积是 `1/(1−e^−0.0077·24)=5.9`，浅室只有 `2.7`，所以单次时只占 6.5% 的深室在稳态后反而主导尾部。这就是「长期服药后终末半衰期会进一步延长」的机制，不需要任何时间依赖参数。

之前几版一直对不上，根因是**只有两相**：要让 6 h 保留八成以上，主相必须够长；但主相一长，24–120 h 的斜率就被拖下来，43.9 h 的标注立刻与曲线矛盾。加进深室相之后三个要求同时成立 —— 而且「长期给药后半衰期继续延长」这个现象是线性叠加自动给出的，不是额外手工加的时间依赖参数。1.0.14 之前的深室份额（8%）偏大、深室半衰期（78.6 h）偏短，稳态斜率只有 61.4 h；把份额降到 6.5%、深室拉到 90 h 之后，单次 53.7 h 与稳态 78.7 h 同时对上 Kuhnz 的两个实测值。

### 为什么 3→8 h 的割线做不陡（1.0.12 的核算）

原本打算加一个吸收滞后时间（Tlag）去同时凑 Tmax 3 h 与早期斜率 4 h，算完发现这条路走不通，理由是两个硬约束：

1. **割线由 AUC 和终末相锁死。** 若 3→8 h 真要按 4 h 衰减，C(8) 就必须从 140 掉到 59；而 8 h 之后按 43.9 h 终末相衰减，光这一段尾巴的面积就有 59 ÷ 0.0158 ≈ 3 737 ng·h/mL，已经超过总 AUC 目标 3 450。也就是说「Cmax 140 @3 h + AUC 3 450 + 终末 43.9 h」这三个锚点本身就决定了 C(8) ≈ 82、割线 ≈ 6 h。Frolich 报的 3.3–4.2 h 是**分室拟合里的 α 相速率常数**，不是相邻两点算出来的斜率；本模型的「早期相 t½ = 2 h」对应的就是这个 α，两者并不矛盾。
2. **想把割线压到 4 h 的取值是非物理的。** 网格搜索里唯一能给出 4.4 h 的组合是「Tlag 1 h + 早期相 1.2 h」，此时 ka 0.554 < kf 0.578 —— 吸收比快相消除还慢，系数 ka/(ka−kf) 变号，曲线出现负幅度，峰值扫描失真。这是 flip-flop 伪解，不是更好的模型。为此在 `PkProfile` 里加了硬下限 `ka ≥ 1.05 × kf`，防止以后调参再次踩到。

### 1.0.12 真正改动的是 PD：抑睾改由浓度驱动

旧版用「7 日窗口平均 CPA **日剂量**」驱动 HPG 抑制。稳态没问题（ENIGI 的 10/25/50/100 mg 平台完全对得上），但漏服或停药时剂量窗口会立刻归零，T 在一天之内跳回基线——与刚校正过的 43.9 h、蓄积 ×3 的曲线自相矛盾。现在改成：

```
C̄CPA = 7 日窗口平均 CPA 浓度（同一条曲线解析积分）
D̄CPA = 50 mg × C̄CPA / C̄(50 mg q24 稳态)      ← 等效日剂量
gCPA  = 1 − exp(−D̄CPA / 1.5 mg·d⁻¹)
```

稳态下浓度与剂量成正比，`D̄CPA` 就等于处方日剂量，因此平台行为完全不变：12.5 / 25 / 50 / 100 mg 稳态 T 都是 27.5 ng/dL（0.95 nmol/L），10 mg 为 28.5（0.99 nmol/L），落在 ENIGI 的 0.9–1.1 nmol/L 内。变的只有时间过程，以 12.5 mg/日、停药 30 天为例（基线 550 ng/dL）：

| 停药后 | 0 d | 3 d | 5 d | 7 d | 10 d | 14 d | 21 d | 30 d |
|---|---|---|---|---|---|---|---|---|
| T（ng/dL） | 27.6 | 28.0 | 30.5 | 52.7 | 241 | 457 | 543 | 550 |
| CPA 浓度 | 23.7 | 11.1 | — | 2.4 | — | 0.2 | 0 | 0 |

即「停药后约一周内仍被抑制，1–2 周内回到基线」。这仍然只是情景估算：真实 HPG 轴恢复还涉及受体与促性腺激素的滞后，模型没有单独建这一层。

## 目录

```
app/src/main/java/cn/hrtcurve/app/
  Models.kt          数据模型与枚举、批量记录展开
  PkEngine.kt        药代/药效核心计算与个体拟合
  MainViewModel.kt   状态与业务入口
  LocalRepository.kt 本地持久化
  SecretStore.kt     API Key 加密存储
  AiClient.kt        OpenAI 兼容请求
  Theme.kt           粉白蓝配色与排版
  Components.kt      通用组件与自绘曲线图
  SimulatorScreen.kt 曲线页（单次/批量录入）
  LabsScreen.kt      实测值录入与个体拟合
  FormulaScreen.kt   公式与来源
  AiScreen.kt        API 配置与解读
  MainActivity.kt    入口与底部导航
```

## 参考来源

- ENIGI 队列（CPA 剂量与睾酮抑制）：PMC8571811
- 戊酸雌二醇口服/舌下药代：PMC11246651
- CPA 产品说明书（SmPC）：https://www.medsafe.govt.nz/profs/datasheet/s/Siteronetab.pdf
- CPA 生物等效性研究（2×50 mg：Cmax 242、AUC₀₋∞ 6 908、t½ 74.5 h）：https://s3-us-west-2.amazonaws.com/drugbank/cite_this/attachments/files/000/001/737/original/Monograph_for_cyproterone_acetate.pdf
- Gerber 2024 经皮/口服雌激素药代：PMC11416909
