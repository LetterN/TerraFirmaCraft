/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.entities.ai.pet;

import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntityType;

import net.dries007.tfc.common.entities.ai.livestock.LivestockAi;
import net.dries007.tfc.common.entities.livestock.pet.TamableMammal;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;

import com.mojang.datafixers.util.Pair;
import net.dries007.tfc.common.entities.ai.TFCBrain;
import net.dries007.tfc.common.entities.ai.livestock.BreedBehavior;
import net.dries007.tfc.common.entities.ai.prey.PreyAi;
import net.dries007.tfc.util.calendar.Calendars;

public class CatAi
{
    public static final ImmutableList<SensorType<? extends Sensor<? super TamableMammal>>> SENSOR_TYPES = ImmutableList.of(
        SensorType.NEAREST_LIVING_ENTITIES, SensorType.NEAREST_PLAYERS, SensorType.NEAREST_ITEMS,
        SensorType.NEAREST_ADULT, SensorType.HURT_BY, TFCBrain.TEMPTATION_SENSOR.get()
    );

    public static final ImmutableList<MemoryModuleType<?>> MEMORY_TYPES = ImmutableList.of(
        MemoryModuleType.LOOK_TARGET, MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryModuleType.WALK_TARGET,
        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryModuleType.PATH, MemoryModuleType.LAST_SLEPT,
        MemoryModuleType.BREED_TARGET, MemoryModuleType.TEMPTING_PLAYER, MemoryModuleType.NEAREST_VISIBLE_ADULT,
        MemoryModuleType.TEMPTATION_COOLDOWN_TICKS, MemoryModuleType.IS_TEMPTED, MemoryModuleType.AVOID_TARGET,
        MemoryModuleType.HURT_BY_ENTITY, MemoryModuleType.HURT_BY, MemoryModuleType.HOME, TFCBrain.SLEEP_POS.get(), TFCBrain.SIT_TIME.get(),
        MemoryModuleType.ATTACK_TARGET, MemoryModuleType.ATTACK_COOLING_DOWN
    );

    public static final int HOME_WANDER_DISTANCE = 36;
    public static final int HOME_LOST_DISTANCE = 120;

    public static Brain<?> makeBrain(Brain<? extends TamableMammal> brain)
    {
        initCoreActivity(brain);
        initIdleActivity(brain);
        initIdleAtHomeActivity(brain);
        initRestActivity(brain);
        initRetreatActivity(brain);
        initFollowActivity(brain);
        initHuntActivity(brain);
        initSitActivity(brain);

        brain.setCoreActivities(ImmutableSet.of(Activity.CORE)); // core activities run all the time
        brain.setDefaultActivity(Activity.IDLE); // the default activity is a useful way to have a fallback activity
        brain.useDefaultActivity();

        return brain;
    }

    public static void initCoreActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(Activity.CORE, 0, ImmutableList.of(
            new Swim(0.8F), // float in water
            new LookAtTargetSink(45, 90), // if memory of look target, looks at that
            new MoveToTargetSink(), // tries to walk to its internal walk target. This could just be a random block.
            new CountDownCooldownTicks(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS) // cools down between being tempted if its concentration broke
        ));
    }

    public static void initIdleActivity(Brain<? extends TamableMammal> brain)
    {
        LivestockAi.initIdleActivity(brain);
    }

    public static void initIdleAtHomeActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(TFCBrain.IDLE_AT_HOME.get(), ImmutableList.of(
            Pair.of(0, new RunSometimes<>(new SetEntityLookTarget(EntityType.PLAYER, 6.0F), UniformInt.of(30, 60))), // looks at player, but its only try it every so often -- "Run Sometimes"
            Pair.of(1, new BreedBehavior(0.5f)), // custom TFC breed behavior
            Pair.of(1, new AnimalPanic(1f)), // if memory of being hit, runs away
            Pair.of(2, new FollowTemptation(e -> e.isBaby() ? 1.5F : 1.25F)), // sets the walk and look targets to whomever it has a memory of being tempted by
            Pair.of(3, new BabyFollowAdult<>(UniformInt.of(5, 16), 1.25F)), // babies follow any random adult around
            Pair.of(3, new RunIf<>(CatAi::isTooFarFromHome, new StrollToPoi(MemoryModuleType.HOME, 1F, 10, HOME_WANDER_DISTANCE - 10))),
            Pair.of(4, new RunOne<>(ImmutableList.of(
                Pair.of(new StrollToPoi(MemoryModuleType.HOME, 0.6F, 10, HOME_WANDER_DISTANCE - 10), 2),
                Pair.of(new StrollAroundPoi(MemoryModuleType.HOME, 0.6F, HOME_WANDER_DISTANCE), 3),
                Pair.of(new SetWalkTargetFromLookTarget(1.0F, 3), 2), // walk to what it is looking at
                Pair.of(new DoNothing(30, 60), 1))
            ))
        ));
    }

    public static void initRestActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(Activity.REST, 10, ImmutableList.of(
            new RunIf<>(e -> !e.isSleeping() && isTooFarFromHome(e), new StrollToPoi(MemoryModuleType.HOME, 1.2F, 5, HOME_WANDER_DISTANCE)),
            new RunIf<>(e -> !e.isSleeping(), new CatFindSleepPos()),
            new CatSleepBehavior()
        ));
    }

    public static void initRetreatActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivityAndRemoveMemoryWhenStopped(Activity.AVOID, 10, ImmutableList.of(
                SetWalkTargetAwayFrom.entity(MemoryModuleType.AVOID_TARGET, 1.3F, 15, false),
                createIdleMovementBehaviors(),
                new RunSometimes<>(new SetEntityLookTarget(8.0F), UniformInt.of(30, 60)),
                new EraseMemoryIf<>(PreyAi::wantsToStopFleeing, MemoryModuleType.AVOID_TARGET) // essentially ends the activity
            ),
            MemoryModuleType.AVOID_TARGET
        );
    }

    public static void initHuntActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(TFCBrain.HUNT.get(), ImmutableList.of(
            Pair.of(0, new FollowOwnerBehavior()),
            Pair.of(1, new StartAttacking<>(CatAi::getAttackTarget)),
            Pair.of(2, new MeleeAttack(40)),
            Pair.of(3, new RunSometimes<>(new SetEntityLookTarget(EntityType.PLAYER, 6.0F), UniformInt.of(30, 60)))
        ));
    }

    public static void initFollowActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(TFCBrain.FOLLOW.get(), ImmutableList.of(
            Pair.of(0, new FollowOwnerBehavior()),
            Pair.of(1, new RunSometimes<>(new SetEntityLookTarget(EntityType.PLAYER, 6.0F), UniformInt.of(30, 60)))
        ));
    }

    public static void initSitActivity(Brain<? extends TamableMammal> brain)
    {
        brain.addActivity(TFCBrain.SIT.get(), ImmutableList.of(
            Pair.of(0, new RunSometimes<>(new SetEntityLookTarget(MobCategory.CREATURE, 8f), UniformInt.of(30, 60))),
            Pair.of(1, new RunSometimes<>(new SetEntityLookTarget(EntityType.PLAYER, 8f), UniformInt.of(30, 60))),
            Pair.of(2, new DoNothing(30, 60))
        ));
    }

    public static RunOne<TamableMammal> createIdleMovementBehaviors()
    {
        return new RunOne<>(ImmutableList.of(
            // Chooses one of these behaviors to run. Notice that all three of these are basically the fallback walking around behaviors, and it doesn't make sense to check them all every time
            Pair.of(new RandomStroll(0.5f), 2), // picks a random place to walk to
            Pair.of(new SetWalkTargetFromLookTarget(0.5F, 3), 2), // walk to what it is looking at
            Pair.of(new DoNothing(30, 60), 1))
        ); // do nothing for a certain period of time
    }

    public static boolean isTooFarFromHome(TamableMammal entity)
    {
        return isFarFromHome(entity, HOME_WANDER_DISTANCE);
    }

    public static boolean isExtremelyFarFromHome(TamableMammal entity)
    {
        return isFarFromHome(entity, HOME_LOST_DISTANCE);
    }

    public static boolean isFarFromHome(TamableMammal entity, int distance)
    {
        return entity.getBrain().getMemory(MemoryModuleType.HOME).map(globalPos ->
            globalPos.dimension() != entity.level.dimension() || globalPos.pos().distSqr(entity.blockPosition()) > distance * distance
        ).orElse(true);
    }

    public static boolean wantsToStopSitting(TamableMammal entity)
    {
        var brain = entity.getBrain();
        if (brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).isPresent())
        {
            return true;
        }
        return brain.getMemory(TFCBrain.SIT_TIME.get()).filter(time -> Calendars.SERVER.getTicks() > time + 2000).isPresent();
    }

    public static void updateActivity(TamableMammal entity, boolean doMoreChecks)
    {
        final var brain = entity.getBrain();
        final Activity current = brain.getActiveNonCoreActivity().orElse(null);
        if (current != null)
        {
            if (brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET) && couldFlee(entity))
            {
                brain.setActiveActivityIfPossible(Activity.AVOID);
                return;
            }
            if (doMoreChecks)
            {
                if ((current.equals(TFCBrain.HUNT.get()) || current.equals(TFCBrain.FOLLOW.get())) && entity.getOwner() == null)
                {
                    if (isExtremelyFarFromHome(entity))
                    {
                        entity.setCommand(TamableMammal.Command.RELAX);
                        brain.setActiveActivityIfPossible(Activity.IDLE);
                    }
                    else
                    {
                        entity.setCommand(TamableMammal.Command.RELAX);
                        brain.setActiveActivityIfPossible(TFCBrain.IDLE_AT_HOME.get());
                    }
                }
                else if (current.equals(TFCBrain.IDLE_AT_HOME.get()))
                {
                    if (isExtremelyFarFromHome(entity))
                    {
                        entity.setCommand(TamableMammal.Command.RELAX);
                        brain.setActiveActivityIfPossible(Activity.IDLE);
                    }
                }
                else if (current.equals(TFCBrain.SIT.get()) && wantsToStopSitting(entity))
                {
                    entity.setCommand(TamableMammal.Command.RELAX);
                    brain.setActiveActivityIfPossible(TFCBrain.IDLE_AT_HOME.get());
                }
            }
        }
    }

    private static boolean couldFlee(TamableMammal entity)
    {
        final Activity active = entity.getBrain().getActiveNonCoreActivity().orElse(null);
        if (active != null && active.equals(TFCBrain.HUNT.get()))
        {
            return entity.getHealth() < 5f || entity.isOnFire();
        }
        return true;
    }

    private static Optional<? extends LivingEntity> getAttackTarget(TamableMammal entity)
    {
        final var brain = entity.getBrain();
        if (couldFlee(entity))
        {
            return Optional.empty();
        }
        if (entity.getOwner() instanceof ServerPlayer player)
        {
            final LivingEntity hurtBy = player.getLastHurtByMob();
            if (hurtBy != null && hurtBy.tickCount < player.getLastHurtByMobTimestamp() + 120)
            {
                return Optional.of(hurtBy);
            }
        }
        if (brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY))
        {
            return brain.getMemory(MemoryModuleType.HURT_BY_ENTITY);
        }
        return Optional.empty();
    }
}
