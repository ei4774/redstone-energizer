# 活红石粉连向被充能物品（设计说明）

`redstone_energizer` 侧一条 mixin，在 living_item 算完 dust 连接掩码的那一刻，把「邻居格有可储电物品」
的方向位并进去。living_item 的任何文件都没有改动。

## 链路

- `dev/redstoneenergizer/mixin/RedstonePropagationMixin` —— `@Inject(method = "computeDustConnections(I)B",
  at = @At("RETURN"))`，目标是 `com.qiqi.li.living.domain.redstone.RedstonePropagation`（`targets = "..."`
  字符串，无编译依赖）。
- `dev/redstoneenergizer/mixin/LivingItemMixinPlugin` —— 按 `RedstonePropagation.class` 资源是否存在
  决定这条配置要不要应用（mixin 阶段用不了 `ModList`）。
- `dev/redstoneenergizer/LivingDustLinks` —— 反射读 `context / size / width`，对四邻调
  `ContainerContext#getItem(int)`，有 `Capabilities.EnergyStorage.ITEM` 就 `| (1 << dir)`。
  方向位与 living_item 一致：up=1, down=2, left=4, right=8。
- `redstone_energizer.mixins.json` + `neoforge.mods.toml` 的 `[[mixins]]`。
- 配置 `[living] connectItems`（默认开）。

## 为什么必须注入内部

`computeDustConnections` 每 tick 重算并覆盖组件，且同步包由它的调用方发出。从外部写组件：
下一 tick 被覆盖，且新的值发不到客户端。

## 已知取舍

- 只要邻居格物品有 `IEnergyStorage` 就连线，**不看电量和是否正在充**（满电也连）。
  和 living_item 自己「拉杆/火把无条件连线」一致；要改成「只在真充能时连」需要额外记账。
- 只做活红石粉。火把/拉杆/中继器/比较器没有 `connections` 概念，无从连起。
- 补线（dust 只连上下时多出的一根）是 living_item 自己的画法，本 mod 不参与。
- 排障看日志：反射失败会打一条 `living_item dust links unavailable ...` 的 WARN，成功时打一次
  `living_item dust links active`。
