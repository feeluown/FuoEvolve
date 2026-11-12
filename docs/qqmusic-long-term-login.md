# QQ 音乐长期登录（A2：授权码 → 可续期会话）

> **English abstract**: QQ Music web-login cookies can never be renewed, so an expired session used to
> surface as "音源接口响应已变化，请更新应用" and could only be fixed by logging out. This document
> records the verified protocol that replaces it: the platform web-login hosts intercept the single-use
> OAuth `code` from `y.qq.com/portal/wx_redirect.html`, the provider exchanges it for a `musickey`
> (3-day lifetime) plus a `refresh_token` (≈60-day grant), and renews the key before it expires. All
> numbers below come from a live account, not estimation.

> **状态**：核心闭环已落地（跨模块契约 + `provider/qqmusic` 实现 + 三端浏览器拦截），`provider:qqmusic` / `:shared` / `feature:providerauth` 单元测试通过；**真机冒烟未做**。
> **关联**：[#307](https://github.com/feeluown/FuoEvolve/issues/307)（主）、[#306](https://github.com/feeluown/FuoEvolve/issues/306)、[#283](https://github.com/feeluown/FuoEvolve/issues/283)。
> **本文所有数字均来自真实账号实测**，不是推测；实测方法见第 5 节。

---

## 0. 结论摘要

| 问题 | 结论 |
| --- | --- |
| 能不能做到长期登录 | ✅ 能。用网页登录拿到的**授权码**换 `musickey` + `refresh_token`，再定时续期 |
| 一次扫码能用多久 | ≈ **60 天**（`expired_at` 恒定在授权时刻 + 60 天，续期不会延长） |
| `musickey`（真正登录态）寿命 | **3 天**（`keyExpiresIn = 259200` 秒） |
| 多久续一次 | **1.5 天**（`musickeyCreateTime + keyExpiresIn / 2`）；App 启动/回前台机会式续期即可 |
| 需要 `refresh_key` 吗 | ❌ 不需要。该登录类型返回空 `refresh_key`，仅凭 `refresh_token` 即可续期（实测连续两次成功） |
| 授权码能复用吗 | ❌ 一次性。重放返回 `req.code = 1000`，因此**必须在重定向页执行前拦下导航** |
| 需要 refresh 材料之外的东西吗 | `openid` / `unionid` / `access_token` / `musicid`，兑换响应一次全给 |

一句话：**把 QQ 音乐登录从"抓网页 cookie"升级为"截授权码 → 兑换可续期会话 → 定时续期"**，一次扫码的免登录期从「随时失效」变成「约 60 天」，且失效时不再需要先退出登录。

---

## 1. 背景：为什么"把 cookie 存好"注定失败

改造前 QQ 音乐用 `ProviderLoginMode.WebView` 打开 `y.qq.com`，登录完成后抓 cookie，只拿到 `uin` + `qm_keyst`（`qm_keyst` 与 `qqmusic_key` 同值，就是 `musickey`）。

三个硬事实使这条路无法长期化：

1. **网页 cookie 里没有任何续期材料**。`refresh_token` / `refresh_key` / `openid` / `unionid` 只出现在登录服务器（`QQConnectLogin.LoginServer` / `music.login.LoginServer`）的**响应体**里，不写 cookie。
2. **网页端自己也不续期**。反编译 `y.qq.com` 的主 bundle 后确认：客户端只是读取 `qqmusic_key || p_skey || skey || p_lskey || lskey` 算 `g_tk`，全站没有 `musickey` 刷新逻辑，只有 `psrf_qqopenid` 这类身份 cookie。所以「定时打开网页保活」这条路不成立。
3. `musickey` 只有 3 天寿命，到期后服务端一律回业务码 `1000`。

因此之前的修复（#307）只能做到**发现失效 + 一键重新登录**，无法消除重登。

---

## 2. 方案对比与选型

| 方案 | 做法 | 一次扫码免登录 | 改动量 | 结论 |
| --- | --- | --- | --- | --- |
| A1 原生扫码 | App 内显示 `ptqrshow` 的 PNG 二维码，自己跑 `ptqrlogin → check_sig → oauth authorize → QQLogin` | ≈60 天 | 大（二维码需三端 `actual` 解码 + 完整扫码状态机） | 可行但重 |
| **A2 复用 WebView 截授权码** | 保留 WebView，把登录页换成 `graph.qq.com/oauth2.0/authorize`，在重定向页加载前拦下 `code`，再由 Provider 兑换 | ≈60 天 | 中（三端各一处导航拦截 + Provider 兑换/续期） | **本次采用** |
| B WebView 保活 | 定时加载 `y.qq.com` 让站点自己轮换 key | 不成立 | 小 | ❌ 第 1.2 条已证伪 |
| C 维持现状 | 只做失效探测 + 一键重登 | 随时失效 | 0 | 已上线，作为 A2 的兜底 |

选 A2 的关键理由：复用现有三端 WebView 与登录流程，不需要二维码解码，且**授权码兑换后的会话可以反推出 cookie**，所有既有请求路径零改动。

---

## 3. 登录态模型与实测生命周期

| 字段（兑换/续期响应） | 实测值 | 含义 |
| --- | --- | --- |
| `musickey` | `Q_H_` 前缀，95 字符（续期后 97） | 真正登录态；写入 `qm_keyst` 与 `qqmusic_key` cookie |
| `refresh_token` | 32 字符 | 续期凭据；**不需要 `refresh_key`** |
| `refresh_key` | **空字符串** | 该登录类型不返回，也不需要 |
| `access_token` | 32 字符 | 续期请求必填 |
| `openid` / `unionid` | 非空 | 续期请求必填 |
| `musicid` | 9 位数字 | 续期请求必填，等价 `uin`（`o` 前缀需剥离） |
| `encryptUin` | 12 字符 | `encrypt_uin`，部分「我的」接口用 |
| `musickeyCreateTime` | 兑换时刻（秒） | 续期判定基准 |
| `keyExpiresIn` | **259200 秒 = 3 天** | `musickey` 寿命 |
| `expired_at` | 兑换时刻 + **5 184 000 秒 = 60 天** | 授权/令牌整体到期；**续期不延长** |
| `needRefreshKeyIn` | `0` | 该类型无 refresh_key 轮换 |
| `loginType` | `2` | 2 = QQ 登录 |

---

## 4. 协议

### 4.1 授权入口与「惰性重定向」技巧

登录页直接用 QQ 音乐的 OAuth 授权地址（`QQMusicProviderDefinition.LOGIN_URL`）：

```text
https://graph.qq.com/oauth2.0/authorize
  ?response_type=code
  &client_id=100497308
  &redirect_uri=<encode(https://y.qq.com/portal/wx_redirect.html?login_type=9&surl=https%3A%2F%2Fy.qq.com%2F)>
  &scope=get_user_info,get_app_friends
  &state=state&from_ptlogin=1&src=1
```

**交付实现的取值是标准重定向 `login_type=1`**：三端都会在导航到达 `wx_redirect.html` 时**取消该导航**（Android `shouldOverrideUrlLoading`/`shouldInterceptRequest` 返回 true 或空响应、iOS `decidePolicyFor` 取消、desktop `RequestInterceptor` 返回 `Reject`），页面永不执行，单次 `code` 不会被消费。保留标准 query 的好处是：即使某端拦截失效，用户仍能走通登录（拿到不可续期的 cookie 会话），不会卡在空白页。

**备用技巧（实测有效，仅供手工验证使用）**：`wx_redirect.html` 的内联脚本只在 `login_type` 为 `1`（QQ）或 `2`（微信）时才发起兑换，其它值什么都不做。把 `login_type` 写成非法值（如 `9`）即可让页面自己"惰性化"：

- `graph.qq.com` 照常完成授权（实测：它**不校验 `redirect_uri` 的 query 部分**）；
- 重定向页不会消费授权码，`code` 安静地留在 URL 里；
- 不需要任何网络拦截，只要在导航发生时读到 URL 即可。

第 5 节的实测就是用 `login_type=9` 完成的；App 内实现不依赖它。

### 4.2 授权码兑换

```http
POST https://u.y.qq.com/cgi-bin/musicu.fcg
Content-Type: application/x-www-form-urlencoded      # 注意：body 其实是 JSON 字符串
Origin: https://y.qq.com
Referer: https://y.qq.com/portal/wx_redirect.html

{"comm":{"g_tk":5381,"platform":"yqq","ct":24,"cv":0},
 "req":{"module":"QQConnectLogin.LoginServer","method":"QQLogin","param":{"code":"<CODE>"}}}
```

成功：`top.code=0`、`req.code=0`、`req.data` 含第 3 节全部字段。失败：`req.code=1000`（码无效/已用/授权过期），`104400/104401` 同类。

### 4.3 会话续期

```http
POST https://u.y.qq.com/cgi-bin/musicu.fcg
Content-Type: application/x-www-form-urlencoded

{"comm":{"g_tk":5381,"platform":"yqq","ct":24,"cv":0},
 "req":{"module":"music.login.LoginServer","method":"Login",
        "param":{"openid":"…","access_token":"…","refresh_token":"…",
                 "expired_in":<expired_at(秒)>,"musicid":<musicid>,
                 "musickey":"<当前 musickey>","refresh_key":"","loginMode":2}}}
```

成功返回一份**新的**会话（新 `musickey`，以及新的 `refresh_token`/`access_token`）。**必须无条件持久化返回的 `refresh_token`**：是否轮换未验证，按会轮换处理才安全。

### 4.4 会话 → Cookie 合成

除授权码外不依赖任何浏览器状态，直接由会话推导 cookie：

| cookie | 来源 |
| --- | --- |
| `uin` | `musicid` |
| `qm_keyst` | `musickey` |
| `qqmusic_key` | `musickey` |
| `encrypt_uin`（可选） | `encryptUin` |
| `login_type` | `2`（QQ） |

这样 `QQMusicProvider` / `QQMusicContentProvider` / `QQMusicUserLibrary` 等既有请求路径**完全不用改**，`qqToken()` 的 `g_tk` 推导也保持不变。

### 4.5 传输层 quirk（改动前必读，别"顺手修"）

1. **`Content-Type: application/x-www-form-urlencoded` + JSON body** 是官方网页的真实写法。实测**两种 content type 返回完全一致的响应**（同一请求分别用 form-urlencoded 与 application/json 各打一次，得到同样的 668 字节 `req.code=1000` 业务错误），说明网关只解析原始 body、忽略该头，因此实现直接用 `postJson`（application/json）即可，不需要为此在 `provider/runtime` 增加新方法。
2. **域名回退**：`u.y.qq.com` → `szu.y.qq.com` → `shu.y.qq.com`（网页端逻辑如此）。
3. **`g_tk` 固定 5381**：全新登录时没有旧 key，`hash33("") = 5381`；兑换/续期都用这个常量。
4. **两套 `login_type` 编号别混**：重定向 URL 里 `1=QQ / 2=微信`；而 `comm.tmeLoginType` 是 `1=微信 / 2=QQ`。续期用 `loginMode=2`。
5. **User-Agent 无关**：兑换与续期都不校验 UA/Referer，纯 Ktor 请求即可（实测用 curl/Node 直接通过）。
6. 兑换响应会 `Set-Cookie` 把会话写进浏览器；但**我们不需要它**，cookie 由 4.4 自行合成。

---

## 5. 实测记录（可复现）

### 5.1 方法

用 DSH 的 browser-skill 驱动本机 Chromium 的 Agent Window（已登录的浏览器 profile）：

1. 打开 4.1 的授权页（`login_type=9` 惰性重定向）；
2. 人工扫码/密码登录并授权；
3. 落到 `https://y.qq.com/portal/wx_redirect.html?…&code=…` 后，**在页面内**用 `fetch` 完成兑换与续期；
4. 只回传脱敏事实（字段是否存在/长度/数值），**授权码与令牌本身从未离开页面、未写入磁盘、未进入对话**。

### 5.2 三次实验的结果

| 实验 | 动作 | 结果 |
| --- | --- | --- |
| 1 | 兑换新 `code` | `req.code=0`；`musickey`(95, `Q_H_`)、`refresh_token`(32)、`access_token`(32)、`openid`、`unionid`、`musicid`(9 位)、`encryptUin`(12)、`keyExpiresIn=259200`、`expired_at=+60d`、`loginType=2`、`refresh_key` 空 |
| 1 | 重放同一个 `code` | `req.code=1000` → **一次性** |
| 2 | 新 `code` 兑换后**立即续期** | `req.code=0`，`musickeyChanged=true`（95→97），`refresh_token` 仍存在 |
| 2 | 用续期后的新会话**再续一次** | `req.code=0` → **续期链连续可用** |
| 3 | 兑换后调 `music.UserInfo.userInfoServer/GetLoginUserInfo` | `req.code=0` → 兑换出的会话**立刻可用于业务接口** |

补证（反向）：网页 bundle 内**没有** `musickey`/`refresh_token`/`LoginServer` 字样，只有 `qqmusic_key`/`p_skey`/`skey` 的读取 → 网页端不会自己续期。

### 5.3 与参考实现的一致性

- [`luren-dc/QQMusicApi`](https://github.com/luren-dc/QQMusicApi) 的 `qqmusic_api/modules/login.py`（`refresh_credential` / `check_expired`）、`models/request.py`（`Credential` 字段与 `is_expired()`）、`core/executor.py`（用 `uin + musickey` 合成 `qm_keyst`/`qqmusic_key`）与本次实测完全吻合。
- [`落月 API`](https://github.com/luoyue712/api-doc/blob/107400c72338921b5608b466e56d145418efe594/src/v2/%E9%9F%B3%E4%B9%90%E6%A8%A1%E5%9D%97/QQ%E9%9F%B3%E4%B9%90/12-refreshCookie.md) 的 `keyExpiresIn=259200` 与我们实测一致；其"可刷新 3–6 个月"的说法偏乐观，我们的实测上限是 **60 天**。
- [`某音乐 cookie 长期有效方案`](https://juejin.cn/post/7423403181994737674) 的结论（必须走扫码/登录服务器拿 `refresh_token`，不能靠网页自动化）与本次结论一致。

---

## 6. 代码实现（当前已落地）

分层原则：**provider 拥有协议与凭据，平台只负责把授权码递上来，应用层只负责状态与 UI**。

### 6.1 数据模型（`:provider:runtime`）

`ProviderCredentials` 增加一个可空的 `session` 字段；`ProviderSessionCredential` 承载可续期会话：

| 字段 | 说明 |
| --- | --- |
| `authKey` | `musickey` |
| `refreshToken` / `accessToken` | 续期凭据 |
| `openId` / `unionId` / `accountId` / `encryptedAccountId` | 续期与「我的」接口所需身份 |
| `authKeyCreatedAtMillis` / `authKeyExpiresInMillis` | 续期判定 |
| `grantExpiresAtMillis` | `expired_at`；到期后只能重新授权 |

配套判定（`ProviderSessionCredential`）：`shouldRenewAt(now)`（过半即续）、`isAuthKeyExpiredAt(now)`、`canRenewAt(now)`、`isGrantExpiredAt(now)`。

### 6.2 Provider 契约（`:provider:runtime` + `:provider:api`）

| 契约 | 作用 |
| --- | --- |
| `KotlinMusicProvider.verifySession()` | 允许"问服务端/本地会话状态"，默认等同 `authState()`；只有 QQ 音乐覆盖 |
| `KotlinMusicProvider.loginWithAuthorizationCode(code, loginType)` | 用授权码登录；默认抛 `UnsupportedOperationException`，不影响其它音源 |
| `ProviderAuthRepository.loginWithAuthorizationCode(providerId, code)` | 应用层入口，默认抛不支持；`KotlinProviderRepository` 透传给 provider |
| `ProviderAuthState.failure` | 让"会话失效"能带着原因上报，UI 才能给一键重登 |

### 6.3 QQ 音乐实现（`:provider:qqmusic`）

| 文件 | 职责 |
| --- | --- |
| `QQMusicAuthorization.kt` | 兑换 (`exchangeAuthorizationCode`)、续期 (`renewSession`)、cookie 合成 (`qqSessionCookies`)、会话映射 (`sessionFromPayload`)、失败归因 |
| `QQMusicCompositeProvider.kt` | `loginWithAuthorizationCode` / `verifySession` 状态机；续期后写回凭据并让 `authState()` 立即反映新状态 |
| `QQMusicProviderDefinition.kt` | `LOGIN_URL` 改为 OAuth 授权地址；cookie 组保留为手工兜底 |
| `QQMusicSession.kt` | 改造前的旧能力（`GetLoginUserInfo` 探针、`qqAccountId`、非 JSON 风控页归类）保留，作为 cookie-only 登录的兜底 |

`verifySession()` 的判定顺序：

```text
存在 session.authKey ?
  ├─ grant 过期            → 未登录 + 「授权已过期，请重新登录」
  ├─ 已过半(keyExpiresIn/2) → 续期
  │      ├─ 成功           → 已登录（新 key 已写回 cookie）
  │      ├─ 失败           → 未登录 + 失败原因（降级到一键重登）
  │      └─ 续期后 grant 过期 → 未登录 + 「授权已过期」
  ├─ key 已过期且不可续期   → 未登录 + 「登录已失效」
  └─ 其它                  → 已登录
否则（历史 cookie-only 登录）→ 走旧的服务器探针（GetLoginUserInfo），失效则未登录
```

### 6.4 三端拦截

| 平台 | 位置 | 做法 |
| --- | --- | --- |
| Android | [`ProviderWebLoginActivity.kt`](../androidApp/src/main/kotlin/org/feeluown/mobile/ProviderWebLoginActivity.kt) | **双通道**：`shouldOverrideUrlLoading`（点击/JS 导航）**与** `shouldInterceptRequest`（覆盖 302 重定向目标 —— WebView 对 3xx 不会回调前者）都匹配 `y.qq.com/portal/wx_redirect.html` + 非空 `code`。命中后用 `AtomicBoolean` 在工作线程抢占（后者运行于 WebView worker 线程，cookie 回调在主线程），再切主线程以 `EXTRA_AUTHORIZATION_CODE` 回传；`shouldInterceptRequest` 返回空 `WebResourceResponse` 阻止页面执行 |
| iOS | [`IosWebLoginOutput.kt`](../shared/src/iosMain/kotlin/org/feeluown/mobile/IosWebLoginOutput.kt) | `decidePolicyFor` 取消该导航并回调 `onAuthorizationCode` |
| Desktop | [`DesktopProviderWebLogin.kt`](../shared/src/desktopMain/kotlin/org/feeluown/mobile/DesktopProviderWebLogin.kt) | `RequestInterceptor` 拦 `wx_redirect`（`WebRequestInterceptResult.Reject`），返回 `DesktopWebLoginResult.AuthorizationCode`；登录 URL 为 OAuth 授权页时会**抑制 cookie 轮询提前完成**，避免旧 jar 里的同名 cookie 抢先给出不可续期会话 |

三端都把「拿到 code」作为一等结果，cookie 路径继续保留给其它音源与手工兜底。
授权码全程不落日志、不进崩溃上报。

### 6.5 应用层接线

`MainActivity` / `IosAppHost` / `DesktopAppHost` → `ProviderAuthFeatureController.loginWithAuthorizationCode(providerId, code)` → `ProviderSessionRepository`（串行化 + 状态/错误上报）→ `KotlinProviderRepository` → `QQMusicCompositeProvider.loginWithAuthorizationCode`。

### 6.6 变更文件清单

```text
provider/api/          ProviderRepositoryContracts.kt（loginWithAuthorizationCode + 默认实现）
provider/runtime/      ProviderSupport.kt（ProviderSessionCredential、credentials.session、verifySession、loginWithAuthorizationCode）
                       CapabilityDelegatingProvider.kt（能力转发）
provider/qqmusic/      QQMusicAuthorization.kt（新增）
                       QQMusicCompositeProvider.kt / QQMusicProviderDefinition.kt / QQMusicSession.kt
shared/                KotlinProviderRepository / ProviderSessionRepository / ProviderAuthFeatureFactory
                       ProviderCredentialBackup.kt（导出剥离 session）
androidApp/            ProviderWebLoginActivity.kt / MainActivity.kt
shared(iosMain)        IosWebLoginOutput.kt / IosAppHost.kt
shared(desktopMain)    DesktopProviderWebLogin.kt / DesktopAppHost.kt
```

---

## 7. 刷新调度与降级

| 时机 | 动作 |
| --- | --- |
| App 启动 / 回前台（目录刷新时 `refreshUserInfo=true`） | `verifySession()`：过半就续期 |
| 任意业务请求返回 `1000` | 归为 `LoginExpired` → UI 给「重新登录」，不误导成"接口已变" |
| 续期失败但 grant 未过期 | 保留会话，下次再试；仍报未登录 + 原因 |
| grant 过期（≈60 天） | 必须重新授权，文案明确写周期 |

不做后台常驻定时器：3 天 key 只需用户 1.5 天内开一次 App 即可维持；长期不打开则自然落到重新登录，代价一次扫码。

---

## 8. 安全与合规

1. **存储**：`refresh_token` 等价长期账号凭据，沿用现有加密存储（Android AES-GCM + AndroidKeyStore / iOS Keychain / desktop OS keyring）。
2. **导出**：`ProviderCredentialBackup` 在**明文**导出中剥离 `session`；**密码加密**导出保留 `session`，这样恢复后无需重新扫码。恢复命中带 `session` 的凭据时走 `refreshAuthState`（而不是 `loginWithCookies`），否则会把 refresh 材料降级掉。
3. **日志**：授权码、`musickey`、`refresh_token` 一律不得进日志/崩溃上报；`shouldOverrideUrlLoading` 的注释已明确此约定。
4. **风控**：续期频率 1.5 天一次；`104604`（过频）、`20279`（设备数超限）、`20277/20278`（账号受限）走独立文案，不要都算"登录失效"。
5. **合规**：属于对非公开接口的逆向使用，协议可能随时变化；必须有"识别失败即降级重登"的兜底（现有 `LoginExpired` 分类即是）。

---

## 9. 风险与未验证项

| 项 | 现状 | 影响 |
| --- | --- | --- |
| `refresh_token` 是否轮换 | 两次续期都返回 32 字符，未比较值 | 低：实现已"每次都用返回的新 token" |
| 60 天到期后的行为 | 未测 | 中：决定文案与预期；预计必须重扫 |
| 微信登录路径（`login_type=2` → `open.weixin.qq.com` → `strAppid=wx48db31d50e334801`） | 未测 | 中：目前只覆盖 QQ 账号 |
| 真机三端拦截 | 仅编译 + 单测 | 高：需手动冒烟（见第 10 节） |
| 协议变更 | 无版本协商 | 中：`req.code` 归因 + 降级重登兜底 |
| 二维码/授权页改版 | `LOGIN_URL` 参数写死 | 低：改常量即可 |

---

## 10. 测试与验收

### 10.1 已通过

```text
./gradlew --offline :provider:qqmusic:desktopTest      # 含 QQMusicAuthorizationTest / QQMusicSessionTest
./gradlew --offline :shared:desktopTest :feature:providerauth:desktopTest
./gradlew --offline checkArchitectureBoundaries
./gradlew :androidApp:compileDebugKotlin
```

单测覆盖：授权码兑换成功 / 去除首尾空白 / 重放返回 1000、续期成功并换新 key、grant 过期、key 过期且不可续期、cookie 合成、失败归因；明文导出剥离 `session` / 加密导出保留 `session`（`ProviderCredentialBackupSessionTest`）；`ProviderSessionRepository.loginWithAuthorizationCode` 的状态发布（`ProviderSessionRepositoryTest`）。

### 10.2 待补测试

1. `ProviderSessionRepository`：`loginWithAuthorizationCode` **失败**时错误落库且不污染已有会话。
2. 三端拦截的 URL 匹配（可抽成 common 的纯函数后单测）。
3. 续期并发：两个 `verifySession()` 同时过半时只发一次续期请求（已有 Mutex，缺并发用例）。

### 10.3 待人工验收（真机）

1. Android：全新安装 → QQ 音乐「网页登录」→ 扫码 → 确认跳转被拦、登录成功、`我的` 可用。
2. 把设备时间/会话强制到 `keyExpiresIn/2` 之后（或直接把 `authKeyCreatedAtMillis` 前推）→ 冷启动 App → 确认自动续期且**无需重新扫码**。
3. 断网续期 → 确认降级为未登录 + 一键重登，且不丢失 refresh 材料。
4. 「导出凭据（明文）」→ 确认导出文件**不含** `session`。
5. iOS / desktop 重复 1–3。

---

## 附录 A：手工验证脚本要点

不依赖本 App 也能复现核心结论：

1. 浏览器打开 4.1 的授权页（`login_type=9`，`surl` 同域），完成登录授权；
2. 落到 `wx_redirect.html?…&code=…` 后，在 Console 里执行一次 4.2 的 POST（`fetch`，`credentials:'include'`）；
3. 再用返回的 `musickey/refresh_token/openid/unionid/musicid/access_token` 执行 4.3，连续两次；
4. 观察：第一次兑换 `req.code=0`、第二次同码 `req.code=1000`、两次续期均 `req.code=0` 且 `musickey` 改变、`keyExpiresIn=259200`、`expired_at` 不随续期前移。

> 提示：授权码与令牌都是一次性/长期凭据，验证时只在页面内存里使用，不要贴到聊天、日志或脚本参数里。
