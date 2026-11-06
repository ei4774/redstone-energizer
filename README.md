# 红石充能 (Redstone Charging)

作者 **Deepseek**

> 开发说明：本模组的代码与文档由 DeepSeek 辅助完成。

Minecraft **1.21.1** / **NeoForge 21.1.x**

> 内部 id 是 `redstone_energizer`；模组列表里显示英文名 **Redstone Charging**，配置面板里的文字是中文；配置文件叫 `redstone_energizer-common.toml`，jar 叫 `redstone_energizer-<版本>.jar`。
>
> 面向玩家的百科式介绍见 [docs/wiki-zh.md](docs/wiki-zh.md)。

给机器或电池接上红石信号，它就会持续收到 Forge Energy（FE）。**默认只按基础功率充，与信号等级无关**：`fePerTick` 默认 1，信号只要大于 0 就是 1 FE/t。想让信号越强充得越快，把「信号等级作为倍数」打开，1 级就是 1 倍、15 级 15 倍。

给容器通电，里面的工具也会被充电；**任何**有物品栏的方块都算，不用配白名单。站在红石块或被强充能的方块上时，背包里的物品也会被充能（默认关闭，见下文）。

## 行为

- 对**任何一个暴露了 FE 能量能力（`IEnergyStorage`）的方块**生效：机器、能量立方、电池、储电多方块等，只要它接受能量。
- 触发条件是「该方块本身收到红石信号」——任意一侧（红石线、拉杆、按钮、红石火把、比较器、侦测器等都可以）。
- **默认（`signalScaling = false`）**：速率恒为 `fePerTick`（默认 1 FE/t），1 级和 15 级充得一样快。取六个方向中最强的信号只是为了判断「通没通电」。
- **打开 `signalScaling` 后**：速率 = `fePerTick × 信号强度`，1 级 1 倍、15 级 15 倍，红石线衰减会直接体现在供能上；活红石超过 15 级的信号也照乘。
- 信号只要大于 0 就充。
- 这是**凭空产生**的能量，不消耗红石或燃料。
- 信号消失、方块被拆、区块卸载时会自动停止；重新上电后立刻恢复，不需要重新放置方块。
- 多个面同时有红石时，拆掉其中一个面不会中断供能——只要还剩任意一面有信号就继续。

## 容器充电

- **没有白名单**：只要方块暴露了物品栏能力（`IItemHandler`），就会被当成容器充能。原版箱子、木桶、潜影盒、漏斗、发射器、投掷器、熔炉，以及 Mekanism、抽屉、AE2 这些模组容器都能直接工作。
- **只充单个物品**：数量大于 1 的堆叠一律跳过（不拆堆、不搬动、也不按堆叠数翻倍）。
- **双层箱子**：任意一半通电就充整个库存，靠 `ItemStack` 身份去重，不会充两遍。
- **节奏**：每 `intervalTicks`（默认 20）tick 处理一次，每次给每件物品 `fePerTick × intervalTicks` FE（打开倍数时再乘信号等级），所以平均速率仍是每 tick 的基础值。
- **同时 / 顺序**：`simultaneous = true` 时一次充所有物品；`false` 时从第一格开始，只充第一个还能收能量的物品，它满了才轮到下一格。
- **排除项**：`blacklist` 里写方块 id 可以跳过特定方块，支持 `namespace:*` 通配；`maxSlots` 跳过槽位过多的方块（默认 512，0 = 不限）。

## 玩家背包充电

**默认关闭**（`players.enabled = false`），需要时在配置里打开。

开启后，玩家**站在会给周围提供红石信号的方块上**时，背包里的可充电物品会收到能量。判定的是脚下那一格：

- **红石块**：直接算有信号。
- **被强充能的方块**：方块本身正在向邻居输出红石信号，比如红石火把垫在下面、中继器/比较器指向它。这类方块能给旁边的机器供电，站上去同样能充。
- **不算**：只是被红石线“弱充能”的方块（红石线指向它但带不动机器），以及红石块旁边的普通方块——红石块不给相邻方块强充能，它只直接给元件供电。
- 速率和给机器供电一样：默认就是 `fePerTick`，与脚下方块输出的强度无关；打开「信号等级作为倍数」后才按强度比例算。
- 覆盖整个玩家库存：主手、副手、快捷栏、背包、护甲；旁观模式跳过。
- 站上去挂机就会充，不用手持、不用交互；跳起来那一下会短暂断开（脚底下变成空气），落地继续。

## 活红石联动（需要 living_item）

装了 **活物品（living_item）** 时，容器（含玩家背包）里的**活红石**会给它周围的物品供能（按容器网格算：9 列的箱子就是 ±1 与 ±9，3×3 发射器是 ±1 与 ±3）。

- **方向**：活红石粉 / 拉杆 / 按钮 / 红石块给**上下左右四格**；**活红石火把给三格**——除了它贴着的那一面，和它自己往外送信号的范围一致（默认「朝上」= 跳过下面一格；没配置方向 `(0,0)` 也按这个算，因为 living_item 的边映射把零向量算成下）；**活中继器、活比较器只给它朝向的那一格**，箭头指哪就充哪。指向的格子是空的或不能充电的东西，就一点都不充。
- 支持的元件：活红石粉（信号原值，可超过 15）、活红石火把 / 活拉杆 / 活按钮 / 活中继器 / 活比较器（开 = 该堆叠的信号上限）、活红石块（恒为上限）。活红石灯是消费者，不供能。
- **组件缺省 = 默认值**：living_item 在数据等于默认值时会**把组件删掉**（省 NBT），所以「读不到组件」不等于「没在发光」——默认是点亮、朝上的火把根本没有组件。读不到就退回它自己的 `of(stack)` 默认值。
- 速率和红石供能一套：`fePerTick`，默认与信号强度无关；打开「信号等级作为倍数」后才按强度比例算。
- 活红石的信号上限随堆叠数提高（那是 living_item 自己的规则：1 个是 15，N 个是 N²）。
- 多个活红石同时够到同一格时**取最强信号**，不叠加；中继器和红石粉指向同一格也是取强的那个。
- 读的是它物品上的 `living_item:living_*_data` 组件，**没有编译期依赖**；没装、id 变了、或反射不到，功能自动静默关闭。
- 配置在 `[living]`：`enabled`（默认开）、`connectItems`（默认开，见下）。
- 列数是按槽位数推断的（9 / 5 / 3×3），推断不出的按 9 列处理。
- 活红石只充单个物品，堆叠物品（数量 > 1）跳过。

### 活红石粉连线

活红石粉的图标会朝**上下左右相邻的可储电物品**画一条连接线，和它朝拉杆、红石火把画线是同一套逻辑。

- 判定只看「那一格物品有没有 `IEnergyStorage` 能力」：满电的电池也照样连线——线表示接线关系，不是电量。
- 线是 living_item 自己画的，而它**每 tick 重算并覆盖** `connections` 掩码，所以本 mod 是在**它算完的那一刻**把方向位并进去（mixin 注入它的 `computeDustConnections`）。从外部写组件不行：下一 tick 就被覆盖，而且没有同步包发给客户端。
- **图标里多出来的那半根线不是本 mod 画的**：living_item 有个「补线」规则——粉只连上下时会补一根向下的线（只连左右时补一根横向的），让灰尘图案看起来是穿过去的。所以「上面连了红石块、右边连了电池、下面却还有一小截线」是它的固定画法，不代表下面真有东西。
- 纯显示，不影响信号传播：红石粉不会因此去「强充能」那一格物品。
- `[living] connectItems = false` 可以关掉；`enabled = false` 时连线也一起消失（不给不充能的物品画线）。
- 没装 living_item 时这条 mixin 配置整个不应用（配置插件按目标类是否存在判定），不报错、不崩。

## 兼容性

- **Mekanism（通用机械）及其附属**：能量会通过每个面的能量能力注入。Mekanism 的无面（side-less）能量接口虽然报告 `canReceive=true`，但可能拒绝外部插入；所以本 mod 先试无面，再依次试六个面，谁真正收下能量就用谁。
- 其它只暴露单一面能量接口的机器同理，不需要额外配置。

### 关于“只有偶数能量”

Mekanism 内部用焦耳（J），换算率是 **1 FE = 2.5 J**，两个方向的换算都用 `long` 截断：

| 本 mod 请求 | 换算成 J | 存进去的 J | 换回 FE |
|---|---|---|---|
| 15 FE | 37.5 → **37** | 37 | 14.8 → **14** |
| 14 FE | 35 | 35 | 14 |
| 13 FE | 32.5 → **32** | 32 | 12.8 → **12** |
| 1 FE | 2.5 → **2** | 2 | 0.8 → **0** |

所以奇数 FE 会被“吃掉”1 点，看起来就像只认偶数。这是 Mekanism 的整数换算特性，不是本 mod 的四舍五入。

修正方式：每台机器维护一个 `carry`（本 tick 没送出去的量），下一 tick 加回去再送；上限 1000 FE，避免机器满了以后攒出一个大爆发。长期平均因此精确等于配置值——实测 15 FE/t 在 10 秒内充入 3016 FE（301.6 FE/s ≈ 15.08 FE/t）。

## 配置

首次启动后生成 `config/redstone_energizer-common.toml`（文件里的注释是英文；游戏内面板是中文，见下一节）：

```toml
# Redstone feeding: a powered machine or battery gets FE every tick.
[energy]
	# Base FE per tick. Flat rate when signal scaling is off; multiplied by
	# the signal level when it is on. 0 turns redstone feeding off.
	fePerTick = 1
	# true: the redstone signal level (1-15, higher for living redstone)
	# multiplies fePerTick.
	signalScaling = false

# Container charging: powered containers charge the items inside them.
[containers]
	# Charge items in any block that exposes an item handler.
	enabled = true
	# Ticks between two charges; also the per-event multiplier.
	intervalTicks = 20
	# true: one event delivers intervalTicks worth of energy.
	applyMultiplier = true
	# true: every item at once; false: one slot at a time.
	simultaneous = true
	# Block ids that are never charged; "namespace:*" wildcards work.
	blacklist = []
	# Skip blocks with more slots than this; 0 = no limit.
	maxSlots = 512

# Player charging: standing on a redstone source charges your inventory.
[players]
	# Charge while standing on a redstone block or a strongly powered block.
	enabled = false
	# Ticks between two charges; also the per-event multiplier.
	intervalTicks = 20
	# true: one event delivers intervalTicks worth of energy.
	applyMultiplier = true
	# true: the whole inventory at once; false: one slot at a time.
	simultaneous = true

# Living redstone (living_item mod): charges the items around it.
[living]
	# Charge items from living redstone. Idle without living_item.
	enabled = true
	# Draw connection lines from living redstone dust to chargeable items.
	# Visual only.
	connectItems = true
```

> NeoForge 还会在数值项下面自动补 `# Default: ...` / `# Range: ...` 两行，上面省略了。

举例（默认 `fePerTick = 1`，倍数开关默认关闭）：

| 信号强度 | 默认（`signalScaling = false`） | 打开倍数 |
|---|---|---|
| 1（刚接上红石线末端） | 1 FE/t | 1 FE/t |
| 5 | 1 FE/t | 5 FE/t |
| 15（拉杆/红石块直连） | 1 FE/t | 15 FE/t |

配置在加载和重载时读入一份内存快照，之后每个 tick 只读快照，不再碰配置文件。所以生效方式分两种：

- **游戏内面板**：关闭面板时存盘并触发一次配置重载，立刻生效。
- **手动改 TOML**：不会自动重载（实测等 6 秒也没反应），要重启游戏或服务端。

`fePerTick = 0` 等于临时关闭。

## 游戏内配置面板

不用改文件：模组列表里找到 **Redstone Charging**，点 **Config** 按钮，就能在游戏里改全部选项（四个分页：红石供能 / 容器充电 / 玩家背包充电 / 活红石联动）。

- 用的是 NeoForge 自带的通用配置界面，随版本自动适配，没有自己画 UI。
- 中英文都有翻译：中文环境显示中文标签，选项说明（tooltip）直接取配置里的注释。
- 改完按 Done 退回模组列表就生效：关闭面板时 NeoForge 会存盘并触发 `ModConfigEvent.Reloading`，本 mod 收到后重建缓存快照。
- 面板只在客户端出现；专用服务器上没有它，但配置本身照常工作（服务端用的是同一个 `-common.toml`）。

## 构建

需要 **JDK 21** 和 **Gradle 8.13**（或任意 8.x）。项目没有附带 wrapper，用本机 Gradle 即可：

```powershell
$env:JAVA_HOME = "<你的 JDK 21 路径>"
gradle build --no-daemon
```

产物：`build/libs/redstone_energizer-<版本>.jar`

> 首次构建会下载 Minecraft / NeoForge 依赖，网络不通时可在 `gradle.properties` 里配置自己的代理或镜像。
> `runServer` 任务用于开发期冒烟测试，工作目录是 `runs/server/`。

## 安装

把 jar 丢进 `.minecraft/mods/`（客户端）或服务端 `mods/` 目录。纯服务端逻辑，客户端装不装都能连，但单人游戏需要装在客户端。

## 实现说明

不新增方块、物品或配方，只做三件事：

1. **发现**：区块加载、方块放置、以及方块更新（`/setblock` 也会发）时，记录所有带能量能力的方块（candidates）和所有带物品栏能力的方块（containers）。区块加载是在**工作线程**上触发的，那里不能读相邻区块的方块状态（会加载区块并和主线程互等，触发服务端看门狗），所以加载时只登记候选，供电状态延到下一 tick 在主线程解析。
2. **供能**：维护「当前通电」的子集（active），每 tick 只遍历这个子集；红石变化通过 `NeighborNotifyEvent` 刷新，另有每 100 tick 一次的全量校正，避免漏事件导致停止供能。
3. **充物品**：每 `intervalTicks` 遍历通电容器，以及站在充能方块上的玩家，把能量推进物品的 `IEnergyStorage`。

性能上每 tick 的开销与**正在通电的方块数**成正比，而不是与加载的方块实体总数成正比。玩家充电只查脚下那一格（六次 `getSignal`），与机器数量无关；配置则是每 tick 一次 `volatile` 读。
