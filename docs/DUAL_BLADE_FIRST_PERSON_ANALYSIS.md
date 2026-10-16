# 第一人称双刃渲染实现分析

## 当前故障

疾影型配置为双刃，但原版 `ItemInHandRenderer` 对“空副手”只进入方法、不绘制手臂。旧代码又只在已经处于左手分支时补画左手，因此条件永远无法成立。更严重的是，旧代码直接调用 `PlayerRenderer.renderLeftHand`，跳过了原版第一人称手臂的平移、旋转和镜像，手臂就会落在摄像机/头部附近。

## 开源方案对比

- Better Combat：将主手与副手作为独立攻击通道，双持时交替选择攻击手和动画。它依赖 Player Animator，强项是第三人称骨骼攻击，项目也明确提示双持逻辑、玩家模型修改和攻击时序会与其他实现冲突，不适合直接作为本模组的第一人称渲染依赖。
- Dual Swords：重点是副手伤害、招架、突进与附魔规则，不提供装甲手背武器所需的双臂相机空间渲染。
- 原版 1.21.1：`renderHandsWithItems` 本身已经维护左右手的独立装备进度和镜像锚点；应复用其私有 `renderPlayerArm`，而不是从摄像机根节点直接画 `PlayerRenderer`。

## 采用方案

1. 保留服务端一次攻击只提交一次伤害，双手只是同一技能的视觉与交替攻击手状态。
2. 主手沿用原版 `renderArmWithItem` 通道。
3. 疾影型副手为空时，通过 Mixin Invoker 调用原版 `renderPlayerArm`，获得完整的副手相机变换。
4. 左右手分别采样 `BladeCameraPose`；攻击手走 LOAD/CONTACT/FOLLOW_THROUGH，支撑手走独立的较小动作。
5. 装甲外壳和背刃继续由 `FirstPersonArmorArmMixin` 挂在对应的 `PlayerModel` 手臂骨骼上，所以模型、装甲、刀刃不会彼此脱离。
6. 禁止再直接调用 `PlayerRenderer.renderLeftHand/renderRightHand` 作为第一人称补手方案。

参考：
- https://github.com/ZsoltMolnarrr/BetterCombat
- https://github.com/Provismet/Dual-Swords