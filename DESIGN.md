# GTNH 机器索引 / 机器雷达（Machine Radar）· 设计方案

> 目标：**不靠记忆找机器**。按一个键，屏幕上叠出附近所有 GT 机器（小机器 / 多方块分开算）的类型、控制器位置、舱室分布与运行状态；再给一个可搜索的列表，点一下就知道在哪。

对标：你已有的 `bom-hud/DESIGN.md` 是同一种写法 —— 先盘现成方案，再定数据层，最后才是渲染。

---

## 0. 结论先说

| 问题 | 答案 |
|---|---|
| 做得到吗 | 做得到，而且是 GTNH 2.9 里**信息最全**的一类附属 mod —— 因为 GT 把该暴露的都暴露了（`public` 字段 + 结构定义 API） |
| 最难的哪一步 | 不是渲染，是**"舱室属于哪台机器"的归属判定**。好消息：不用反向查，从控制器往下读就行 |
| 纯客户端能做吗 | **单机能，多人不能**。舱室清单 / 运行状态 / 结构定义只有服务端有 → 服务端索引 + 客户端渲染（照抄 FindIt 的架构） |
| 要不要改 GT | 不用。全部走公开 API，零 mixin（GT++/TecTech 的兜底可能要 1~2 个反射） |
| 工作量 | 单机 MVP 3~5 天；含列表/搜索/离屏箭头 2 周；含地图/持久化 3~4 周 |

---

## 1. 现成方案盘点（2026-11 / 2.9.0 现状）

| 方案 | 有什么 | 差什么 |
|---|---|---|
| FindIt（2.9 自带） | NEI 里按 `Y`：服务端 ±radius 扫 TE，按 Block+Meta 匹配，客户端红色高亮 | 只能按**方块类型**搜，不认 MTE；不区分控制器/舱室；没有列表、没有状态 |
| StructureLib 全息（Hint） | 手持构造触发器右键控制器 → 半透明方块叠出"该放什么" | 只在**建造**时有用；已建成的机器不给你任何信息 |
| BlockRenderer6343 | NEI 里 3D 预览多方块结构 | 纯 GUI 预览，和世界里的实例无关 |
| HWYLA / WAILA | 准星指着控制器 → 名字、电量、进度 | 一次只能看一个，且要先用眼睛找到它 |
| Xaero / JourneyMap 路径点 | 手动打点 | 手工维护，机器一多就废 |
| `/gt` 系列命令 | 调试用 | 无聚合视图 |

**结论：没有"按机器聚合 + 舱室清单 + 世界内叠加"的东西。这是空缺。**

---

## 2. 分三层做，别一次做完

| 层 | 内容 | 价值 | 难度 |
|---|---|---|---|
| **L1 标签层** | 世界内：每个控制器头顶浮一个名字 + 状态色 + 距离；准星指向时画整机包围盒 + 舱室彩点 | 解决"这台是什么、在哪、舱室齐不齐" | 低 |
| **L2 索引层** | 服务端维护 `Map<dim, Map<BlockPos, MachineRecord>>`，支持按类型/电压/状态查询 | 解决"我的离心机都分布在哪" | 中 |
| **L3 导航层** | 列表/搜索面板 + 离屏箭头 + 点到点连线 + 可选小地图 | 解决"我要去那台" | 中 |

先 L1 + L2，L3 的列表是 L2 的直接产物，一天的事。

---

## 3. 数据从哪来（全部已核实，GT5U master ≈ 2.9）

### 3.1 枚举机器

```java
// 服务端 tick 里扫
TileEntity te = world.getTileEntity(x, y, z);
if (!(te instanceof IGregTechTileEntity igte)) continue;
MetaTileEntity mte = igte.getMetaTileEntity();   // 机器本体
if (mte == null) continue;

boolean isMulti = mte instanceof MTEMultiBlockBase;   // 大机器 = 多方块控制器
```

- 小机器 = 其余 `MetaTileEntity`（`MTEBasicMachine` / `MTETieredMachineBlock` 等），一台方块一台机器。
- 大机器 = `MTEMultiBlockBase` 子类，**控制器方块本身就是这台机器**，其余方块是外壳/舱室。

### 3.2 类型 / 图标 / 电压

| 用途 | 取法 |
|---|---|
| 类型 key（稳定） | `mte.mID` + `mte.getMetaName()`（存盘/网络用这个，别用显示名） |
| 显示名 | `mte.getLocalName()`（跟随语言） |
| 图标 | `mte.getStackForm(1)` → 直接 `RenderItem` 画 |
| 电压 | `mte.mTier`（0=ULV…）；显示用 `GTValues.VN[tier]` |
| 所属 mod | 类名包名判断（`gregtech` / `gtPlusPlus` / `tectech` / `gtnhintergalactic`…），用于分组和兜底 |

### 3.3 舱室（关键：从控制器往下读，不要反向查）

`MTEMultiBlockBase` 的舱室列表是 `public` 的，直接拿：

```java
MTEMultiBlockBase mb = (MTEMultiBlockBase) mte;
mb.mInputBusses, mb.mOutputBusses,          // 物品
mb.mInputHatches, mb.mOutputHatches,        // 流体
mb.mEnergyHatches, mb.mDynamoHatches,       // 电入 / 电出
mb.mMaintenanceHatches, mb.mMufflerHatches, // 维护 / 消声
mb.mDualInputHatches, mb.mExoticEnergyHatches, mb.mExoticDynamoHatches,
mb.mCryotheumHatches, mb.mPyrotheumHatches  // 特殊
```

每个 hatch 都是 `MTEHatch`（`getBaseMetaTileEntity().getXCoord()/getYCoord()/getZCoord()` 拿绝对坐标）。
`mCoils` 还能直接给出线圈方块列表 —— 顺手就能显示"这台 EBF 是几级线圈"。

> **为什么不做反向**：`MTEHatch` 里没有 parent 指针（只有 `watchers`，那是给智能输入仓推配方检查用的）。反向查一定会踩到 wallshare 的坑，见 §7。

### 3.4 结构轮廓

```java
IStructureDefinition<MTEIndustrialMacerator> def = multi.getStructureDefinition();
```

- `getStructureDefinition()` 返回 StructureLib 的 `IStructureDefinition`，内部是 `addShape(name, String[][])` 的字符串布局 + 每字符的 `IStructureElement`。
- 配合 `getExtendedFacing()`（96 种朝向）把它旋转到世界坐标，就能算出**整机包围盒**，甚至逐格描边。
- **只在服务端算**，把 bbox（6 个 int）塞进网络包发给客户端 —— 客户端不碰 GT 结构 API，省掉一堆类加载和 null 风险。

**兜底**：GT++ / TecTech / 部分老多方块不实现 `IConstructable`（`getStructureDefinition()` 拿不到）→ 退化成「控制器 + 所有 hatch 的 AABB 外扩 1 格」。视觉上够用。

### 3.5 运行状态

| 字段 | 含义 |
|---|---|
| `mMachine` | 已成型 / 结构有效 |
| `mProgresstime` / `mMaxProgresstime` | 当前配方进度（>0 且 <max = 正在跑） |
| `mEUt` | 当前耗电（负=发电） |
| `mEfficiency` / `lastParallel` | 效率 / 并行数 |
| `mPollution` | 污染产出 |
| `checkRecipeResult` | **停机原因**（缺电/缺料/输出满）—— UI 上直接显示，比"为什么不动"的玄学强 |
| `recipesDone` | 累计配方数 |
| `mOutputItems` / `mOutputFluids` | 当前输出（可做"满了"预警） |

状态机建议只依赖 `mMachine` + `mProgresstime/mMaxProgresstime` + `checkRecipeResult` 三件事，别去解析 `mUpdate` 的私有语义（会随版本变）。

---

## 4. 架构（照抄 FindIt 的分工）

```
[服务端]                                          [客户端]
ChunkEvent.Load/Unload ──┐
ServerTickEvent(分帧扫描) ├─> MachineIndex (per-dim Map<BlockPos, Record>)
                          │        ├─ 可选：WorldSavedData 落盘
                          │        └─ 查询(按键/命令/开面板) ──> S2C 分包 ──> ClientCache
                          └─ 状态差分(每 20 tick，只推变化) ──────────────┤
                                                                          ├─ RenderWorldLastEvent（gizmo）
                                                                          └─ GuiScreen / ModularUI2 面板
```

要点：

1. **扫描分帧**：每 tick 最多扫 8~16 个 chunk 的 TE 列表，别一 tick 扫完。首次进服 500 台机器 ≈ 2~3 秒扫完，无感。
2. **chunk 卸载即剔除**（但保留 record 的"最后已知"标记，用于列表里显示"未加载"）。
3. **差分推送**：全量只在"首次/打开面板"时发；平时只发变化的 record（状态位变化、结构失效）。
4. **单机也要走这套**：integrated server 就在同一进程，但**数据必须在 server tick 里读**，client 线程读 MTE 字段会 CME / 读到半更新状态。

### 4.1 网络包（SimpleNetworkWrapper）

```
MachineSyncPacket {
  int dim; boolean full;
  int count;
  Record[] {
    int x,y,z;            // 控制器
    int mteId; short tier; byte side;   // 类型 + 朝向（类型名客户端自己查表）
    short bbox[6];        // 整机包围盒
    byte status;          // 0 未成型 1 待机 2 运行 3 故障
    byte flags;           // 发电 / 维护未修 / 输出满 ...
    short progress;       // 0~1000
    byte hatchCount;
    Hatch[] { int x,y,z; byte kind; }   // kind: 0 物品入 1 物品出 2 流体入 3 流体出 4 电入 5 电出 6 维护 7 消声 8 ME 9 激光
  }
}
```

一台机器 ≈ 24 + 12×N 字节，500 台 ≈ 30 KB → 按 8 KB 分页，别撞 32 KB 包上限。
**类型名不进包**：客户端有 GT jar，`MTETypeRegistry` 按 `mteId` 反查名字和图标即可（省一半带宽，且自动跟随语言）。

---

## 5. 渲染（L1 的全部价值都在这）

`RenderWorldLastEvent` + `partialTicks` 平移，老 GL 就够：

```java
double px = x - RenderManager.renderPosX; // 同理 y/z
GL11.glDisable(GL11.GL_TEXTURE_2D);
GL11.glDisable(GL11.GL_DEPTH_TEST);        // 穿墙；不想穿墙就保留 depth
GL11.glEnable(GL11.GL_BLEND);
GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
GL11.glLineWidth(2.0F);
Tessellator t = Tessellator.instance;
t.startDrawing(GL11.GL_LINES);
t.setColorRGBA_I(color, alpha);
t.addVertex(px, py, pz); t.addVertex(px + 1, py + 1, pz + 1);
t.draw();
```

| 元素 | 画法 | 备注 |
|---|---|---|
| 整机包围盒 | 12 条线，颜色=状态 | 未成型红色虚线感（交替跳段） |
| 控制器 | 1×1×1 亮框 + 头顶名字 | 名字用 `fontRenderer` billboard |
| 舱室 | 小方块 / 半透明面片，按 kind 上色 | 物品=蓝、流体=青、电入=黄、电出=橙、维护=红、消声=灰、ME=紫 |
| 状态徽标 | 名字前一格图标（√ / ⏸ / ⚠） | 用 `RenderItem` + 自绘 |
| 离屏箭头 | 屏幕边缘指向目标，带距离 | `GuiIngame` 层画，找机器最实用 |
| 连线 | 玩家→目标 的虚线 | 点击列表项后 5 秒 |

性能红线：

- **距离剔除**：默认 64 格内画标签，128 格内只画包围盒，更远只进列表。
- **视锥剔除**：`Frustrum`/手写 dot 判断，500 台机器时这步是生死线。
- **合批**：所有线框塞进**一个** `Tessellator` 批次，别每台 startDrawing 一次（500 次 draw call 直接掉帧）。
- **文字限流**：同屏最多 30 个标签，按距离排序取前 N；字体渲染才是真瓶颈。
- **默认关**：只在按住热键 / 打开"雷达模式"时渲染，别常驻。

---

## 6. 需要你拍板的三件事

1. **只做单机，还是多人也要？** 多人 → 服务端也得装，且要处理权限（普通玩家能不能看别人基地的机器清单，建议默认只看自己周围 + OP 全图）。
2. **要不要碰 GT++ / TecTech / 星际**？要 → 兜底逻辑 + 可能的反射；不要 → 只认 `MTEMultiBlockBase`，代码少一半。
3. **L3 地图做到哪**：自绘俯视图（工作量 1 周，能画产线走向）／只做列表+箭头（2 天）／直接调 Xaero API 打 waypoint（1 天，但不给"全局地图"）。

---

## 7. 坑（按踩到的概率排序）

| 坑 | 说明 | 处理 |
|---|---|---|
| **Wallshare** | 2.9 明确增强了共享墙（Pyrolyse Oven 等），两台机器的外壳/舱室可能贴着同一批方块。按几何归属必错 | 永不做几何归属；只按控制器自己的 hatch 列表聚合。一个 hatch 只属于一台机器（GT 源码注释明说） |
| **客户端读 MTE** | 客户端 TE 常常只有 block+meta，MTE 实例可能为空或状态陈旧 | 服务端权威，客户端只信包 |
| **结构定义拿不到** | GT++/TecTech/部分老多方块不实现 `IConstructable` | AABB 兜底；UI 上标"结构未知" |
| **结构跨 chunk** | 一个多方块横跨 4 个 chunk，按 chunk 存归属会丢 | 索引按 BlockPos 全局存，不按 chunk 归属 |
| **region 文件没加载** | 远处机器"不存在" | 列表里区分「已扫描 / 未加载」，别显示成"已拆除" |
| **2.9 结构 rework** | 大量多方块改结构（HTGR、Large Boiler、Pyrolyse…） | 只依赖 `IStructureDefinition` 动态算，绝不硬编码尺寸 |
| **mteId 不稳定** | 跨版本/跨整合包 ID 会变 | 存盘只存"当前会话缓存"，别当长期数据库；真要做历史统计用 `getMetaName()` 字符串 |
| **字体/GL 状态污染** | 画完不恢复 → 聊天框、血条错位 | 严格 push/pop + 恢复 `GL_DEPTH_TEST`/`GL_TEXTURE_2D`/`glColor` |
| **500+ 机器** | 包体积、内存、draw call 三杀 | 分页、差分、合批、距离/视锥剔除 |

---

## 8. 代码结构（建议新建 mod，别塞进 bom-hud）

```
moe.takochan.machineradar
├── MachineRadar.java            @Mod 入口，注册 keybind / 网络 / 事件
├── CommonProxy / ClientProxy
├── index/
│   ├── MachineRecord.java       数据模型（服务端 & 客户端共用 DTO）
│   ├── HatchRef.java
│   ├── MachineIndex.java        per-dim Map，增删改查
│   ├── MachineScanner.java      ServerTickEvent 分帧扫描 + chunk 事件
│   └── RecordBuilder.java       MTE -> Record（唯一碰 GT 内部字段的地方）
├── net/
│   ├── PacketSyncMachines.java  全量/分页
│   ├── PacketRequestIndex.java
│   └── PacketClientCache.java   客户端缓存
├── client/
│   ├── ClientIndex.java
│   ├── RadarRenderer.java       RenderWorldLastEvent
│   ├── LabelRenderer.java       字体 billboard + 限流
│   └── OffscreenArrow.java
├── gui/
│   ├── MachineListScreen.java   搜索 / 分组 / 排序 / 点击定位
│   └── RadarConfigScreen.java
└── config/RadarConfig.java      GTNHLib @Config
```

**隔离原则**：`RecordBuilder` 是唯一 import `gregtech.*` 的类。哪天 GT 改了字段，只改这一个文件；GT++/TecTech 的反射也关在这里。

---

## 9. 执行路径

### 阶段 0（半天）：先验证"信息拿得到"
在现有 `NEI-RecipeTree` 工程里临时挂一个 `ServerTickEvent`，按键时把周围 64 格内所有 `MTEMultiBlockBase` 打日志：名字、tier、舱室数量、`mMachine`、bbox。
> 这一步 200 行，能一次证伪所有"拿不到数据"的担心。**别跳过。**

### 阶段 1（2~3 天）：L1 单机标签层
扫描 + 客户端缓存 + 控制器标签 + 包围盒 + 舱室彩点。热键开关。

### 阶段 2（2 天）：L2 列表
`MachineListScreen`：按类型分组、按距离/电压排序、搜索框、点击 → 世界内高亮 5 秒 + 离屏箭头。

### 阶段 3（2~3 天）：状态与告警
进度条、停机原因（`checkRecipeResult`）、"输出满 / 维护未修 / 结构失效"的红点。

### 阶段 4（可选）：持久化 + 地图 + 多人权限

---

## 10. 依赖 & 构建

你的工程已经是 GTNH convention 2.0.20 + RFG，直接加：

```groovy
// dependencies.gradle
api("com.github.GTNewHorizons:GT5-Unofficial:<2.9 对应版本>:dev")   // 现在是 runtimeOnly，要改成 compile
api("com.github.GTNewHorizons:StructureLib:1.4.x:dev")              // IStructureDefinition
api("com.github.GTNewHorizons:GTNHLib:0.6.x:dev")                   // @Config / 工具
// 面板想用现代 UI 再加：
// api("com.github.GTNewHorizons:ModularUI2:2.x:dev")
```

> 现有 `NEI-RecipeTree` 里 GT 是 `runtimeOnlyNonPublishable`，**编译期看不到 GT 类**，新 mod 必须换成 `api`/`implementation`。

---

## 11. 不建议做

- 别试图给每个方块标"属于哪台机器"（wallshare 下无解，且没意义）。
- 别在客户端解析结构定义（类加载 + null 风险，白送崩溃）。
- 别把整机逐格描边当默认（1000 格的多方块会闪瞎眼），默认只画 bbox，准星指向时才明细。
- 别一上来做小地图（L3 里最贵、最不影响"找机器"的那部分）。
