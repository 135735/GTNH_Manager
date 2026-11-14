# 开发环境与构建

这份工程是标准的 GTNH 1.7.10 + RetroFuturaGradle 工程，任何一台能跑 Gradle 的机器都能构建。

## 1. 需要什么

| 项 | 要求 |
|---|---|
| JDK | **25**（GTNH convention 2.0.20 的构建脚本类要求 JVM 25；JDK 17/21 会报 `Dependency requires at least JVM runtime version 25`）。本机放在 `D:\MineCraft_ModDev\toolchain\zulu25.*` |
| 网络 | 首次构建要访问 GTNH Maven、Mojang、Forge Maven（下载 MC/Forge/GTNH 依赖） |
| IDE | IntelliJ IDEA（直接 Open 这个目录，Gradle 工程会被识别） |
| 磁盘 | 首次构建 + 反编译缓存大约 2~3 GB |

Gradle 不用另装，仓库里带了 wrapper（9.3.1），第一次运行会自动下载。

## 2. 构建

```powershell
cd machine-radar
.\build.cmd        # 自动找 ../../toolchain/zulu25* 并执行 build -x spotless*
```

（本机默认禁止运行 `.ps1`，`build.cmd` 用 `-ExecutionPolicy Bypass` 调 `build.ps1`；
想直接跑脚本就 `powershell -ExecutionPolicy Bypass -File .\build.ps1`。）

或者自己设 JDK：

```powershell
$env:JAVA_HOME='D:\MineCraft_ModDev\toolchain\zulu25.38.109-ca-jdk25.0.5.0.3-win_x64'
.\gradlew.bat build -x spotlessJava -x spotlessCheck
```

- `-x spotlessJava -x spotlessCheck`：GTNH 的 blowdryer/spotless 在部分机器上会报环境错误；
  你机器上如果能跑，可以去掉这两个参数。
- 产物：`build/libs/machineradar-0.11.0.jar`（reobf 后、能直接进游戏）。
- `build/libs/machineradar-0.11.0-dev.jar` 是开发环境用的，不要丢进 mods。

把 jar 复制到：

```
<实例>\.minecraft\versions\GT New Horizons 2.9.0-RC-2\mods\
```

> 覆盖 jar 前先关游戏。游戏运行时覆盖会让 JVM 读到写了一半的 zip，
> 报 `ZipException: invalid LOC header` / `ClassNotFoundException`。

## 3. 跑起来

- 主键：`K` 开关，`Ctrl+K` 图层，`Shift+K` dump，`L` 工厂列表，`N` 机器地图，`H` 心电图，`M` 设置界面。
- 游戏内自检：`/mradar status`、`/mradar scan`、`/mradar rescan`、`/mradar dump`、`/mradar list`。
- 结构识别是否成功，看 dump 里的 `blockCount` / `structureError`，不要靠肉眼猜。
- 工厂列表是否正常：按 L 应该立刻看到机器行；没有就是服务端扫描没出数据
  （先 `/mradar status` 看 `te=` / `machines=`）。
- 外轮廓线框是否正常：对着工业高炉应该只有一圈外轮廓；「线框只画点选」开关切换时
  其它机器的线框应该消失/出现。
- 填色是否正常：机器内部不应该再透出颜色；隔着墙看焦点机器时，填色应该被墙挡住，
  但白色外轮廓还在（X 光）。
- 分组文件：`config/machineradar/groups-<世界名>.json`，改完立刻写盘。

## 4. 依赖版本

`dependencies.gradle`：

```groovy
api("com.github.GTNewHorizons:GT5-Unofficial:5.09.54.205:dev")
api("com.github.GTNewHorizons:StructureLib:1.4.45:dev")
```

- 这两个版本对应 GTNH 2.9.0-RC-2 实例里的实际 jar；
- 对应源码已拉到 `D:\MineCraft_ModDev\GT5-Unofficial`（tag `5.09.54.205`）与
  `D:\MineCraft_ModDev\StructureLib`（tag `1.4.45`），查 API/结构定义直接看这份；
- 升级 GT 版本时先确认 `MTEMultiBlockBase.checkStructure` 和 StructureLib 的
  `StructureElementVisitedEvent` 还在（见 `docs/STRUCTURE.md`）；
- GT 必须是 `api`，不能是 `runtimeOnlyNonPublishable`，否则编译期看不到 GT 类。

## 5. 已知的坑

1. **JDK 必须是 25**：GTNH convention 2.0.20 的构建脚本类要求 JVM 25，JDK 17 在解析
   settings 插件时就失败。JDK 8 连 Gradle 都跑不起来。
2. **不能排除 AE2 传递依赖**：GT5U 的 `MetaTileEntity` 实现了 AE2 的
   `ICraftingIconProvider`，排掉就编译失败。
3. **1.7.10 的 `Vec3` 是 `xCoord/yCoord/zCoord`**，不是 `x/y/z`。
4. **reobf**：只有 `build/libs/machineradar-0.11.0.jar` 是 SRG 名、能进游戏；
   开发 jar 里是 MCP 名，进游戏会 `NoSuchMethodError`。
5. **结构方块来源**：`radar/StructureCapture.java` 用 GT 自己的 `checkStructure`
   + StructureLib 仪表采集，别改回 bbox/BFS。
6. **设置界面用反射读写 `RadarConfig` 字段**：加/改配置字段时，
   `GuiRadarSettings` 里滑条/开关的字段名要同步，否则滑条没反应。
7. **列表界面只读 `RadarState.snapshot`**：不要在 GUI 里碰 `gregtech.*`，
   也不要在客户端线程读 MTE 字段。
8. **外表面泛洪**：`HullMesh.of` 用扩展包围盒 BFS 标出外部空气，只给"贴着外部空气"的
   面生成几何；体积超过 `MAX_FLOOD_CELLS`（20 万）会退回旧判断（内壁面会回来）。
   改结构识别时别把这一步删了，否则中空机器的内壁会被填色。
9. **外轮廓是逐帧算的**：`RadarRenderer.emitSilhouette` 用边的两个相邻面朝向判断，
   依赖 `HullMesh.edgeDirs/edgeFaces` 与"共面边已剔除"的前提。
10. **分组是客户端数据**：`MachineGroups` 按存档名存 json，不进服务端；换世界会重新加载。
11. **历史采样在客户端 tick**：`RadarClientTicker` 每 20 tick 调 `MachineHistory.sample()`，
    只在雷达开启时记录；不要把这套逻辑塞进渲染循环（每秒一次 1000 台约 34 µs）。
12. **热路径别做字符串/正则**：`MachineGroups.ensureLoaded()` 用 `World` 引用比较走快速路径，
    只有换世界时才取世界名并做手工清洗；列表行/地图点每帧都会调 `groupOf()`。
13. **线框有数量上限**：全量模式只给最近 `wireframeMaxMachines`（默认 96）台算轮廓；
    逐帧轮廓是客户端渲染里最贵的部分，加机器前先看这个值。
14. **地图图层是舱室端口**：电网/物流/流体图层读 `MachineRecord.hatches`，不是世界里的
    GT 电缆/管道方块；要画真实管道拓扑得在 `RadarScanner` 里额外采集非机器 TE。
15. **地图绘制要裁剪**：大机器足迹会超出地图区域，`GuiMachineMap.drawFootprint` 必须
    clamp 到 `mapX..mapX+mapW / mapY..mapY+mapH`，否则会画到标题和按钮上。
16. **心电图读 MachineHistory**：`GuiEfficiency` 只画 120 个点，不要把它改成每帧遍历世界；
    没有采样（雷达关着）时显示提示而不是报错。
17. **远程开关机走服务端线程**：GUI 只 `RadarActions.submit(new RemoteAction(...))`，
    由 `RadarScanner` 的 ServerTick 里 `RadarActions.process()` 执行；
    不要在客户端线程直接 `writeToNBT`/改 MTE 字段。
18. **分组图标只存 item id + meta**：`MachineGroup.setIcon` 不存 NBT；要支持 NBT 图标得改成
    NBT+Base64（注意 1.7.10 的 `CompressedStreamTools`）。
19. **线框深度预写**：`RadarRenderer.drawOutlines` 在非 X 光模式下先 `ColorMask(false)` +
    `DepthMask(true)` 画一遍外壳，再画轮廓；改这里时记得恢复 ColorMask/DepthMask。
20. **多人**：目前只在单机 integrated server 下工作（服务端扫描 → 同进程快照）。
21. **`focus` 可能是 null**：准星没对着任何机器（或没打中 AABB）时 `pickFocus` 返回 null。
    渲染路径里凡是 `focus` 都要先判空——0.8.0 崩过一次：`drawOutlines` 里直接
    `drawable(focus)`，进游戏开着雷达走两步就 NPE（`Cannot read field "multi" because "rec" is null`）。
    `drawable()` 现在自身也 null 安全，但别依赖它去兜所有 `focus.` 访问。
22. **分组区域 vs 包围盒**：`MachineGroup.region` 是 3D 框选定出来的显式区域（闭区间方块框），
    `MachineGroups.bounds()` **优先返回区域**，没有区域才退回成员 bbox 并集。
    区域按 `regionDim` 区分维度，别在渲染/地图里绕过 `bounds()` 自己算。
23. **批量归组要一次写盘**：`MachineGroups.assignAll()` 是唯一批量入口
    （内部 reindex + save 各一次）；不要写成 for 循环调 `assign()`——那会一台重写一次
    `groups-*.json`，框选一百台就是一百次磁盘写。
24. **选区是纯数据**：`radar/RegionSelection` 不 import 任何 client 类；
    渲染（`RadarSelectionRenderer`）、按键（`RadarKeyHandler`）、归组界面（`GuiGroupAssign`）分开，
    这样选区逻辑可以在没有 GL 的地方单测/复用。换维度要 `clear()`，否则会拿旧维度的框。
25. **列表「加入组…」不再依赖左栏选中**：`GuiGroupAssign` 同时服务列表和世界选区两条入口，
    改归组流程时别又加回「必须先选组」的按钮逻辑。
26. **框选杖靠取消右键工作**：`WandInteractionHandler` 监听 `PlayerInteractEvent`
    （`RIGHT_CLICK_BLOCK`，只在客户端 `world.isRemote` 时处理），拿到杖就 `setCanceled(true)`。
    1.7.10 客户端顺序是 `Minecraft.func_147121_ag` → `ForgeEventFactory.onPlayerInteract`
    → `PlayerControllerMP.onPlayerRightClick`，所以在这里 cancel 掉就不会开机器界面/放方块。
    不要改成 `Item.onItemUse`：那条路在单机里主要走服务端线程，会拿错线程去改客户端选区。
27. **窗口统计是 EMA，不是历史平均**：`MachineHistory.Samples` 里只有 6 个 double
    （run/eu/out × 分钟/小时两个时间常数），每秒采样时用 `1-exp(-dt/τ)` 更新。
    想加严格历史窗口就得多存采样，别把 EMA 当精确值展示。
28. **滚轮单位**：本机跑 lwjgl3ify，`Mouse.getEventDWheel()` 可能返回 ±1 而不是 ±120，
    `GuiMachineList.handleMouseInput` 里两种都认（`wheel/120` 为 0 时退化成 ±1）。
    滑块拖动用 `scrollListTo/scrollGroupsTo` 把鼠标 y 映射成像素偏移，别直接改索引。
29. **分析面板改变布局**：`analysisMode > 0` 时 `panelW` 上限从 820 变 1180，并让出右侧一列
    （`analysisX/W`），列表/详情宽度按剩余空间重算。加新列时记得同步 `initGui` 里的 `inner` 计算。
30. **分组图层**：`RadarState.groupLayer`（G 键，配置 `showGroupLayer`）在 `RadarRenderer.onRenderWorldLast`
    里直接早退到 `drawGroupLayer()`，只画分区框 + 大标签（0.028~0.75）。别在这个分支里再碰
    FOCUS / 线框状态，机器图层那套状态完全跳过。
31. **配方读取靠反射**：`MTEBasicMachine.mLastRecipe` 是 protected，`RecordBuilder` 按类缓存 Field
    （`RECIPE_FIELDS`）只解析一次；GT 升级改了字段名就会静默失效（配方显示空），
    换 GT 版本后先 `/mradar dump` 看 recipe 字段。
32. **产能台账是积分估算**：`ProductionLedger` 每秒把「当前配方 × dt / 配方时长」累加到资源桶。
    秒桶 120、分钟桶 60，槽位 = `time % len`，写入前清槽，所以不需要整体搬移；
    小数用每资源 `pending` 累积后取整，别把 long 桶改成 int（会丢零头/溢出）。
33. **存储机器只认量子箱/缸**：`MTEQuantumChest.getItemStack()/getItemCount()/getMaxItemCount()`、
    `MTEQuantumTank.mFluid + getTankInfo()`。超级箱/超级缸是多方块，别硬塞进 `readStorage()`。

## 6. 代码地图

```
radar/StructureCapture.java   结构方块采集（GT 校验 + StructureLib 事件）
radar/RecordBuilder.java      唯一直接 import gregtech.* 的构建类
radar/MachineRecord.java      快照数据模型（含 allowed/IO 计数）
radar/MachineControl.java     远程 enableWorking/disableWorking
radar/RadarActions.java       客户端→服务端动作队列
radar/RemoteAction.java       一台机器的开关动作
radar/MachineGroup.java       分组数据（成员 = dim:x:y:z + 图标）
radar/RadarScanner.java       ServerTickEvent 扫描 + 动作队列
radar/DumpWriter.java         JSON 导出
radar/RegionSelection.java    3D 选区状态（两角点 / 包含判定 / 机器枚举，纯数据）
radar/RadarState.java         开关 / 图层 / 快照 / 追踪目标
item/ItemRadarWand.java       框选杖物品（右键空气开归组界面；方块右键在 WandInteractionHandler）
client/WandInteractionHandler.java  拿着杖右键方块 = 设角点，并取消原版右键
client/EcgPlot.java           效率曲线绘制（分析面板与独立心电图共用几何）
client/HullMesh.java          外部泛洪筛面 → 外表面 + 轮廓边数据
client/RadarSelectionRenderer.java  世界里的 3D 选区框（12 边 + 角点，穿墙可见）
client/RadarRenderer.java     填色 / 外轮廓（深度预写）/ 分组区域 / 舱室 / 图标 / 追踪
client/RadarHud.java          焦点详情面板 + 追踪横幅
client/RadarText.java         数字/文字格式化（HUD 与列表共用）
client/MachineGroups.java     分组仓库（groups-<世界>.json、显式区域、AABB、图标）
client/MachineHistory.java    客户端历史环形缓冲（2 分钟，分组聚合 + 秒/分/时 EMA 窗口）
client/ProductionLedger.java  分组产能台账（资源产出/消耗，秒桶+分钟桶，类异星工厂）
client/FactoryStats.java      工厂页统计（分组/全局、间隔、分帧）
client/RadarClientTicker.java 每秒采样
client/gui/GuiGroupAssign.java    归组选择器（新建 / 加入 / 替换 + 记录区域），列表与选区共用
client/gui/GuiMachineList.java    工厂列表（多选 / 拖拽 / 批量 / 统计 / 分析面板 / 滚动条）
client/gui/GuiMachineMap.java     2D 多图层地图（足迹 / 单方块 / 电 / 物 / 流 / Y 层）
client/gui/GuiEfficiency.java     效率心电图（单机 / 分组聚合两个视图）
client/gui/GuiGroupName.java      分组改名/删除/图标/区域
client/gui/GuiRadarSettings.java  游戏内设置
config/RadarConfig.java       配置 + 反射读写
```

## 7. 提交前自检

1. `.\build.cmd` 通过；
2. jar 大小变化合理（0.11 版约 190 KB）；
3. 进游戏按 M 能打开设置、按 K 能开关、按 L 能打开列表、按 N 能打开地图、按 H 能打开心电图；
4. 列表里 Ctrl/Shift 多选、Ctrl+A 全选；把行拖到左栏分组上能归组；「开关」能停/启机器；
5. 底部「统计：分组/全局」「分帧」「调试」「区域」「分析」能切换并写进配置；
   调试开着时统计/调试各占一行，不再互相截断；
6. 右键分组 → 用选中机器图标 / 设为当前选区 / 清除区域；世界内能看到分组 AABB / 区块预览框；
7. 选中 3 台机器点「加入组…」→「＋ 新建分组」，新组里应该正好这 3 台；再切「模式：替换成员」
   点另一个组，该组应该只剩这 3 台；
8. 拿「雷达框选杖」右键两个方块 → 选区框出现、归组界面自动打开、台数正确；右键空气能重新打开；
   潜行+右键能清除；框选杖右键不应该打开被点机器的 GUI；
9. 列表滚轮能翻（列表上翻列表、左栏上翻分组），拖动两侧滑块能定位；
10. 「分析：全局」打开后页面加宽、右侧出现曲线 + 全局统计 + 每组分预览行，切「范围：分组」
   后显示该组资源净产出（没有台账数据时退回成员列表）；「窗口：秒/分钟/小时」切换后数值跟着变；
11. 按 G 切分组图层：世界里只剩分区框 + 大标签（组名·台数（运行数）），再按 G 恢复机器图层；
12. 量子箱/量子缸：详情面板显示存储物品/流体与数量/容量；
13. 运行中的单方块机器：详情面板显示「配方 入 … / 出 … · 时长 · EU/t」；多方块应至少显示输出+时长；
14. 分组心电图（列表左栏选组 → 「心电图」）：右侧资源列表有产出/消耗，点资源主图变成双曲线，
    「窗口：2分/15分/1时」切换后曲线点数跟着变；
15. 对着工业高炉看 HUD 的 `N块` 是否 20 上下；
16. `Shift+K` 的 dump 里没有 `structureError`（除非那台机器没成型）。
