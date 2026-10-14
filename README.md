# 铁人三项身份核验 · Android 公开奖励演示源码

这是 PREP39 安全失败诊断候选的公开源码快照，来源为已冻结的 `face-failure-diagnostic-android-candidate/source`。公开副本只做构建标识调整：正式包名 `com.triathlon.prep`，simulation 后缀 `.simulation`；业务源码与冻结源保持一致。

当前快照对应正式调试 APK 的来源身份，不代表该 APK 已上传或可直接用于赛事：

- formal debug APK：`com.triathlon.prep`，SHA256 `E075FEBED3B9ADAA0F9AEA608FE599872C1360CB799D3E77F874E15C33C8BF7B`
- 固定 QA 目标：`60357FB7351C4833CB54585732F3D43A5D8EE51AA0B9AFFF92669BD879A11CBF`
- 本轮 QA 只覆盖安全失败诊断、合成会话和实际生产提示；真实厂家错误原因、读证、相机、在线服务、打印、双屏和设备兼容性仍未完成验证。

## 安全边界

仓库不包含真实名单、证件号、照片、现场照片、私密设备数据、凭据、许可文件、厂家 AAR/JAR、native 模型或 APK。LIVE 源码只保留接口和门禁；缺少私有依赖时不要把源码构建结果当作可部署 LIVE 包。服务地址与凭据必须由受控配置提供，不能写入仓库。

simulation 使用明确的合成数据和模拟适配器，包名为 `com.triathlon.prep.simulation`。模拟核验、分数、小票和打印结果均不是实际身份核验或出纸证明。请勿导入真实名单、证件号码或照片。

## 构建

准备 JDK 17、Gradle 8.11.1 和 Android SDK Platform 35 / Build Tools 35.0.0。进入 `android/` 后运行：

```shell
gradle :app:assembleSimulationDebug
gradle -PcoreOnly :core:check
```

simulation 构建不会调用厂家 SDK、真实服务或设备。LIVE 构建需要合法取得的私有依赖和独立授权，本仓库不提供，也不以缺依赖的失败替代现场验证。

开源依赖声明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 验证边界

这份公开快照用于源码阅读和合成 TEST 构建。QA 目标和 formal APK 的诊断提示不等于真实厂家服务已经成功，也不等于现场设备已通过。当前 workspace 中尚未 QA 的选手预览改动没有进入本快照。
