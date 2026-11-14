# 多方块识别：为什么之前不对，以及 GT 2.9 里唯一权威的解法

本文对应 GT5U 标签 `5.09.54.205`（用户实例 `GT New Horizons 2.9.0-RC-2` 里的
`gregtech-5.09.54.205.jar`）与 StructureLib `1.4.45`。源码来自：

- https://github.com/GTNewHorizons/GT5-Unofficial/tree/5.09.54.205
- https://github.com/GTNewHorizons/StructureLib

## 1. 结论

**不要自己推结构。让 GT 自己校验一次，然后从 StructureLib 的遍历事件里把坐标收下来。**

GT 校验结构时用的是它自己的锚点偏移（写在每台机器 `checkStructure` / `checkPiece` 里的常量），
遍历出来的每个单元都带**绝对世界坐标**。我们只是在校验期间打开 StructureLib 的
“仪表”（instrument），把每个单元的坐标记下来。线圈、各级玻璃、外壳、舱室、控制器全都包括，
旋转/镜像天然正确，也不需要判断方块材质。

## 2. 之前三种方案为什么注定不稳

| 方案 | 失败点 |
|---|---|
| `bbox 内所有非空气方块` | bbox 的锚点是反解出来的，本身就可能偏 1~2 格；而且 bbox 里会混进穿过的管道、隔壁机器的零件 |
| 解析结构串 + 反解 `~` | `StructureDefinition.Builder.addShape` 会把 `~` 和空格都替换成 `\uA000`，最后**整段删掉**（`StructureDefinition.java:187`），锚点信息在 API 层就没了；GT 的偏移根本不写在形状串里 |
| 调 `IStructureDefinition.check()` 返回 true 的锚点 | `check()` 要求**全部**单元命中，舱室等级、线圈等级任何一处不匹配就 false；而且它是“全有或全无”，不能用来打分 |

补充：`MTEMultiBlockBase.checkStructure` 每次都会 `clearHatches()` 再 `checkMachine()`，
所以**借用它做一次校验不会破坏舱室列表**，反而会把舱室重新登记成最新状态。

## 3. 源码链

```
MTEMultiBlockBase.checkStructure(force, te)                 GT5U  MTEMultiBlockBase.java:565
  └─ clearHatches()
  └─ checkMachine(te, stack, errors)                        （各机器实现）
       └─ MTEEnhancedMultiBlockBase.checkPiece(piece, a,b,c, errors)
                                                            GT5U  MTEEnhancedMultiBlockBase.java:337
            └─ IStructureDefinition.iterate(piece, world, facing,
                    x, y, z, a, b, c, walker)               StructureLib  IStructureDefinition.java:366
                 └─ StructureUtility.iterateV2(...)         StructureLib  StructureUtility.java:3363
                      ├─ abc 为形状内坐标
                      ├─ extendedFacing.getWorldOffset(abc, xyz) + 控制器坐标
                      ├─ StructureLibAPI.isInstrumentEnabled() 时：
                      │     StructureElementVisitedEvent.fireEvent(world, x, y, z, ...)
                      │                                          StructureUtility.java:3396
                      └─ walker.visit(element, world, x, y, z, ...)
```

其中 `a, b, c` 就是“控制器在形状里的位置”（`checkPiece` 的 horizontal/vertical/depth offset），
GT 每台机器自己写死，比如中子素压缩机是 `checkPiece(STRUCTURE_PIECE_MAIN, 5, 9, 1, errors)`。
**我们完全不需要知道这个数字**——事件里的 `x/y/z` 已经是算好的世界坐标。

2.9 新的声明式结构（`StructureWrapper`）也一样：`controllerOffset` 从定义文本里的 `~`
解析出来（`StructureWrapper.java:56`），`checkStructureImpl` 把它传给同一个 `iterate`
（`StructureWrapper.java:191`），事件照发。

仪表 API：

```java
StructureLibAPI.enableInstrument(token);   // StructureLibAPI.java:75，线程级
try { machine.checkStructure(true, te); }
finally { StructureLibAPI.disableInstrument(); }
```

注意：

- 仪表是 **ThreadLocal** 的，服务端扫描线程（ServerTick）打开、校验、关闭，不会影响别处；
- 同一线程只允许一个仪表，`enableInstrument` 会抛 `IllegalStateException`，代码里已做互斥检查；
- 事件在 `MinecraftForge.EVENT_BUS` 上同步派发，监听器里只做 `Set<Long>` 写入。

## 4. 本 mod 的实现

`radar/StructureCapture.java`：

1. 每台机器按 `(dim,x,y,z)` 缓存结构方块，TTL 120 秒；失败（未成型）5 秒后重试；
2. 每次扫描最多做 8 次校验，避免一次 tick 卡死；
3. 校验前 `enableInstrument`，校验后 `disableInstrument`；
4. 事件里的坐标进 `Set`，最后补上控制器坐标（控制器在形状里是 skip 位，不触发事件）；
5. 结果写进 `MachineRecord.blocks` 与 `blockCount`，dump 里可以直接看。

`RecordBuilder` 的顺序很关键：**先 capture（会让 GT 重建舱室列表），再读 `mInputBusses` 等列表**，
所以舱室数据也是新校验出来的，不会读到陈旧的。

## 5. 覆盖范围与边界

- 所有走 StructureLib 校验的多方块（GT 本体绝大多数，2.9 的 `StructureWrapper` 也在内）都能拿到精确集合；
- `checkStructure` 被 GT++ 某些 legacy 机器重写、或结构校验完全不经过 StructureLib 的，采集会失败，
  退回“控制器 + 舱室”标记，dump 里 `structureError` 会写原因（`not-formed` / `check-false` / `instrument-busy`）；
- 未成型的机器本来就没有“完整结构”，只显示控制器标记；
- 缓存 120 秒：拆改机器后最多 2 分钟刷新一次，`/mradar rescan` 可立即清缓存重扫。

## 6. 显示

有了精确方块集合后，显示不再需要“猜轮廓”：

- 先对结构包围盒外扩一圈做**外部空气泛洪**：只有紧贴“能连通到外面”的空气的面才生成。
  机器内部封闭的中空空气不会被标记成外部，所以内壁面不生成——这就是“只显示最外层”
  （`HullMesh.of`；包围盒超过 20 万格时退回旧的“邻接非结构方块”判断）；
- 外表面生成时，共面相邻（同一朝向）的边直接丢弃，不会出现“每个方块一圈线”；
- 剩下的边记录被哪几个朝向的面共用；渲染时按摄像机位置判断：两个相邻面一个朝你、
  一个背你，才是当前视角的**外轮廓**（`RadarRenderer.emitSilhouette`）；
- 填色开 `GL_DEPTH_TEST` + 背面剔除 + 关深度写入，被墙/被自身挡住的层自然不出现
  （“只显示最前一层”）；只有 `overlayXray` 才会关深度，面沿法线外扩 0.002 防 z-fight；
- 非焦点线框开深度测试并按距离/状态淡化；焦点线框白色加粗且 X 光，所以目标总能看见；
- 焦点机器的控制器单独关深度绘制，所以“主方块永远可见”；
- 舱室只在聚焦或走到控制器 `hatchNearRadius` 内时显示（小方块 + 白芯）；
- 单方块只在 `singleRadius` 内显示为电压色小点。

不做逐格线框：1.7.10 里既贵又容易在斜视角下糊；现在是“外轮廓 + 只在焦点上强化”。

## 7. 验证方法

进游戏后：

| 看什么 | 期望 |
|---|---|
| `/mradar status` 里的 `struct=成功/总尝试` | 成型机器应该接近 100% |
| `Shift+K` 导出的 dump 里 `blockCount` | 工业高炉 20 上下，大型机器是几百；`0` 表示采集失败 |
| dump 里 `structureError` | 只有未成型/第三方机器才非空 |
| 画面 | 整台机器只被最外层半透明色盖住，没有内壁色、没有格子边；被墙挡住的部分不出现 |
| 线框 | 只有一圈外轮廓；「线框只画点选」开时只剩准星那台 |
| 列表/分组 | L 打开列表，左栏新建分组、加入组、右键改名，重启后还在 |
| 地图 | N 打开 2D 俯视，能看到当前维度的机器点，滚轮缩放 |

如果某台机器 `blockCount` 是 0，把它的 `meta` 和 `structureError` 发出来即可定位是
“没成型”还是“校验没走 StructureLib”。
