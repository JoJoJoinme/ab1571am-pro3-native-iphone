# 参考资料与依赖说明

本项目与 Apple、Airoha、耳机及伴侣软件厂商没有隶属或认证关系。文中的服务名称和型号字段用于描述互操作性实验，不构成原厂身份或认证证明。

- [JacksonsY AB1571AM Earbud Tools](https://github.com/JacksonsY/ab1571am-earbud-tools)：参数研究、RACE 只读查询、双耳转发与运行缓存的参考。
- [RACE Toolkit](https://github.com/auracast-research/race-toolkit)：消息结构、Flash 读取和页校验算法的参考。其通用更新器不作为本案例的 TWS 双耳更新器。
- [Airoha Firmware Parser](https://github.com/ramikg/airoha-firmware-parser)：容器格式研究参考；本案例另与实际启动程序核对。
- [HeadPhone APP 示例库](https://github.com/HtetAungKyaw3/HeadPhone_APP/tree/main/sdkUtApp/libs)：升级辅助程序依赖的 Airoha 参考 SDK 库，版本线索为 `3.5.0.2022122118`。
- [demoMT NVDM core](https://github.com/dangkhoalk95/demoMT)：NVDM 布局和校验算法参考。

厂商 AAR/JAR 不随公开包分发。`tools/sdk-sources.json` 记录所用文件的来源、Git 对象摘要与 SHA-256，下载程序只接受这些文件；这些校验值不授予厂商库的额外使用或再分发许可。使用者需遵守各原始来源的适用条款。

公开材料不包含厂家原始固件或个人设备转储。Android 辅助程序源码是本次实验的操作工具；升级过程仍须遵守精确设备校验与读回流程。
