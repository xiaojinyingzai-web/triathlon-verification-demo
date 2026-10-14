# CEFK 铁人三项核验准备工程

当前为无设备开发/测试工程。赛事名单、核验联合门禁、当前小票及模拟打印已实现；LIVE默认配置为空，不执行真实读证、相机、在线服务或打印。使用厂家CEFK读证/打印与在线人脸适配；标准Android Camera2提供当前会话JPEG抓拍。旧设备专用源/SDK已按PREP20从活动工程移出，历史归档及旧固定候选保留，不进入当前交付。

simulationDebug包名com.triathlon.prep.simulation，使用明确合成数据，任何核验/小票/打印结果均非真实凭证；simulationRelease禁用。LIVE包名com.triathlon.prep，缺参数/权限/授权时不得通过。当前包不含INTERNET；线上服务真实联调需另核协议、权限与已授权参数。

Android minSdk26/target35，已用Android11/API30环境验证模拟流程。实际CEFK实物为横屏；像素尺寸及是否有第二屏待核。800×1280仅原模拟器回归，不是实机目标布局证明，副屏未实现。

源码唯一入口为本目录。Windows无中文路径构建副本通过prepare-04a.ps1产生、VerifyOnly逐项hash核对；不得直接编辑副本。工具目录旧名称保留，不代表设备或SDK依赖。Core检查用verify-core.ps1或Gradle :core:check；app构建任务为:app:assembleSimulationDebug/:app:assembleLiveDebug。Gradle缓存/SDK/JDK不属于可发布产品，真实IDE/断点和厂商native仍待实际电脑与设备验证。

相机通过显式cameraId、系统支持尺寸、方向和ZSL键校验，只接本次曝光/ticket对应JPEG；无参数不猜测。图像检查按EXIF处理旋转，无法判断旋转或镜像则拒绝。标准Android框架FaceDetector仅检测基础人脸数/图像条件，绝不替代厂家在线活体与1:1；准确率/防攻击/相机品质均须真机验证。当前为JPEG单次内存抓拍，不宣称已有实时相机预览。

不得从日志/README取得凭据或填入真实身份资料。脱敏记录位于应用私有区；小票只关联当前名单批次与本次已保存结果，模拟样张双水印。未知打印宽度/编码/USB参数下LIVE打印按钮禁用，接口受理也不等于实际出纸。
