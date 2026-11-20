# 指月（ZhiYue）

指月是一款离线 Android 法会唱诵进度同步应用，将正在播放或识别到的唱诵位置对应到仪轨法本。

- **演示模式**：像音乐播放器显示歌词一样，当前句自动居中，已唱内容随真实播放进度逐字着色。
- **现场模式**：通过麦克风采集声音，将端侧声学特征与内置参考音轨对齐，定位当前段、句、字。
- **完全离线**：法本、音频、参考特征和模型均随应用内置，不依赖服务器。

[APK 下载页](https://37ku2o6i9g2masbq72p.traesite.cn) · [GitHub Releases](https://github.com/qianmiao888/zhiyue-app/releases)

## 内置法会

| 法会 | 段落数 | 内容示例 |
| --- | ---: | --- |
| 瑜伽焰口 | 10 | 杨枝净水、吉祥会启、赞佛偈、召请、七如来等 |
| 梁皇宝忏·起忏 | 6 | 瑶天玉露、虔诚献香花、佛宝赞、僧宝赞等 |
| 药师普佛 | 3 | 戒定真香、药师赞、回向偈 |
| 阿弥陀佛普佛 | 6 | 戒定真香、弥陀赞、赞礼西方、拜愿等 |

共 25 段，法本为演示用节选，以现场法师唱诵为准。

## 使用方式

### 演示模式

1. 在首页选择法会，点击「开始演示」。
2. 应用播放内置录音，法本以滚动歌词形式展示。
3. 当前句自动居中并放大；已唱内容按 `MediaPlayer` 的真实播放位置逐字变为金色。
4. 前奏和句间气口不会提前高亮；每段音频播放完成后自动进入下一段。
5. 可通过「上一段」「暂停」「下一段」手动控制。

演示模式不需要麦克风权限，也不依赖实时识别。

### 现场模式

1. 选择法会和现场模式，允许麦克风权限。
2. 将手机放在能够听清唱诵的位置。
3. 应用完成声学锁定后，才会更新当前段、句、字；未锁定时明确显示「识别中」。
4. 暂停或手动跳段后，会重新听音并锁定，不把手动选择伪造成识别结果。

现场模式针对应用内置四套同源音轨做了定向适配，不是通用语音转文字工具。真机外放、环境噪声、混响和不同唱腔仍可能影响结果，固定数字样本的基准成绩不代表任意真机环境均能达到相同正确率。

## 技术实现

- Kotlin、Jetpack Compose、Coroutines。
- `MediaPlayer` 驱动演示音频，直接读取播放位置，避免系统计时器与音频播放产生累计偏差。
- 法本 JSON 保存段落、逐句时间轴和逐字时间轴，运行期查表定位。
- 现场链路：16 kHz PCM → 400 ms 分析窗 / 100 ms 帧移 → 8192 点 FFT → 32 维 log-mel 特征。
- 两阶段匹配：参考窗口指纹粗筛，再以完整逐帧特征精排，并结合段边界、连续跟踪、重新锁定和亚帧位置修正。
- 无需联网权限；未接入第三方统计或音频上传服务。

## 项目结构

```text
zhiyue/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── assets/
│       │   │   ├── audio/          # 内置 m4a 音轨
│       │   │   ├── ceremonies/     # 法会法本与句字时间轴
│       │   │   ├── features/       # 参考声学特征
│       │   │   └── models/         # mel 滤波器组
│       │   └── java/com/zhiyue/app/
│       │       ├── align/          # 参考特征与声学对齐
│       │       ├── audio/          # PCM、FFT、log-mel 特征提取
│       │       ├── model/          # 法会、段落与句字映射
│       │       ├── session/        # 播放、录音和会话状态
│       │       └── ui/             # Compose 页面与歌词式法本
│       └── test/                   # JVM 单元测试与固定样本基准
├── tools/
│   ├── prepare_assets.py           # 准备音频和参考特征
│   ├── whisper_align.py            # 构建期逐字时间轴对齐
│   ├── verify_timings.py           # 校验句字时间轴
│   └── live_benchmark.py           # 准备识别基准输入
└── dist/index.html                 # APK 下载页源码
```

## 本地构建

### 环境要求

- JDK 17
- Android SDK 35
- Gradle 8.14.3（当前工程未提交 Gradle Wrapper）
- Android 8.0（API 26）或更高版本的设备

在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

构建 Debug 包：

```bash
gradle :app:assembleDebug
```

构建 Release 包：

```bash
gradle :app:assembleRelease
```

APK 输出到 `app/build/outputs/apk/`。

### Release 签名（可选）

签名密钥和口令不会提交到公开仓库。需要签名时，在本机 `local.properties` 中增加：

```properties
zhiyue.storeFile=keystore/your-key.jks
zhiyue.storePassword=your-store-password
zhiyue.keyAlias=your-key-alias
zhiyue.keyPassword=your-key-password
```

密钥库路径相对于 `app/` 模块。也可以通过同名环境变量提供这些配置。未配置签名时仍可构建未签名 Release 包。

## 校验

校验四套法会的句字时间轴：

```bash
python tools/verify_timings.py
```

运行 JVM 单元测试：

```bash
gradle :app:testDebugUnitTest
```

固定数字样本基准覆盖完整段落、不同起播位置、连续播放和 80/100 ms PCM 分块。其 100% 指标仅统计有效法本边界内已发生声学锁定的帧，不包含未锁定帧，也不等同于真机麦克风实测结果。

## 隐私与权限

- 无账号、无注册、无付费。
- 仅声明 `RECORD_AUDIO`，只在进入现场模式时使用。
- 拒绝麦克风权限后，演示模式仍可完整使用。
- 音频在端侧处理，不上传音频或个人信息。

## 内容来源

内置音源来自牟尼佛法流通网公开录音，仅用于个人学习与演示，不作商用。新增法会可按现有 JSON 模板补充法本、音轨、句字时间轴和参考特征，无需修改页面结构。
