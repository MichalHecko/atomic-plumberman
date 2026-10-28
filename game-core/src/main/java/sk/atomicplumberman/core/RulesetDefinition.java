package sk.atomicplumberman.core;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Tunable rules of a round (plan 4.1, 5). Speed is in thousandths of a cell per second, hitbox,
 * corner assist and roll speed in 1/1024 cell (per tick for rolling), times in ticks, chances in percent.
 * Sudden death closes one cell per step during the last {@code suddenDeathTicks}; 0 turns it off.
 * {@code itemWeights} follow {@link Item} order; {@code startAbilities} is a bit set of ability items.
 * {@code killsToWin} above 0 selects the Kills match mode, otherwise {@code winsToWin} decides.
 */
public record RulesetDefinition(int startBombs, int startRange, int fuseTicks, int flameTicks, int speed,
        int hitbox, int cornerAssist, int turnBufferTicks, int roundTicks, int winsToWin, int killsToWin,
        boolean teamDamage, int fillPercent, int itemPercent, List<Integer> itemWeights, int startAbilities,
        int maxBombs, int maxRange, int speedStep, int maxSpeedUps,
        int suddenDeathTicks, int suddenDeathStep, int suddenDeathWarning,
        int rollSpeed, int throwCells, int flightTicksPerCell, int stunTicks, int triggerFallbackTicks,
        int spoogeWindow, int diseaseTicks, int contactCooldown, int shortFuseTicks, int beltSpeed, int warpCooldown) {
    public static final RulesetDefinition CLASSIC = new RulesetDefinition(1, 2, 150, 27, 3000, 634, 123, 6,
            150 * Simulation.TICKS_PER_SECOND, 3, 0, true, 60, 40,
            List.of(10, 10, 6, 4, 3, 3, 2, 2, 2, 1, 4, 1, 2), 0,
            8, 8, 500, 4, 30 * Simulation.TICKS_PER_SECOND, 30, 30,
            128, 3, 4, 18, 90, 12, 600, 60, 60, 32, 60);

    public RulesetDefinition {
        itemWeights = List.copyOf(itemWeights);
        if (startBombs < 1 || startRange < 1 || fuseTicks < 1 || flameTicks < 1 || roundTicks < 1 || winsToWin < 1) {
            throw new IllegalArgumentException("rules: counts and times must be positive");
        }
        if (hitbox < 2 || hitbox >= Simulation.SUB || hitbox % 2 != 0) {
            throw new IllegalArgumentException("hitbox: expected an even value below " + Simulation.SUB);
        }
        if (cornerAssist < 0 || turnBufferTicks < 0 || speedStep < 0 || maxSpeedUps < 0 || killsToWin < 0) {
            throw new IllegalArgumentException("rules: assist, speed-up and kill values must not be negative");
        }
        if (fillPercent < 0 || fillPercent > 100 || itemPercent < 0 || itemPercent > 100) {
            throw new IllegalArgumentException("rules: chances must be 0..100");
        }
        if (itemWeights.size() != Item.values().length || itemWeights.stream().anyMatch(w -> w < 0)
                || itemWeights.stream().mapToInt(Integer::intValue).sum() - itemWeights.get(Item.RANDOM.ordinal()) < 1) {
            throw new IllegalArgumentException("itemWeights: one non-negative weight per item, some item besides RANDOM");
        }
        for (Item item : Item.values()) {
            if (!item.ability() && (startAbilities & item.bit()) != 0) {
                throw new IllegalArgumentException("startAbilities: " + item + " is not an ability");
            }
            for (Item other : Item.values()) {
                if (item.conflictsWith(other) && (startAbilities & item.bit()) != 0 && (startAbilities & other.bit()) != 0) {
                    throw new IllegalArgumentException("startAbilities: " + item + " conflicts with " + other);
                }
            }
        }
        if (suddenDeathTicks < 0 || suddenDeathTicks > roundTicks || suddenDeathStep < 1 || suddenDeathWarning < 0) {
            throw new IllegalArgumentException("rules: sudden death must fit in the round with a positive step");
        }
        if (maxBombs < startBombs || maxRange < startRange) {
            throw new IllegalArgumentException("rules: limits must not be below starting values");
        }
        if (rollSpeed < 1 || Simulation.SUB % rollSpeed != 0 || beltSpeed < 1 || Simulation.SUB % beltSpeed != 0) {
            throw new IllegalArgumentException("rollSpeed, beltSpeed: must divide " + Simulation.SUB + " so bombs stop on cell centres");
        }
        if (warpCooldown < 0) throw new IllegalArgumentException("warpCooldown: must not be negative");
        if (throwCells < 1 || flightTicksPerCell < 1 || stunTicks < 0 || triggerFallbackTicks < 1 || spoogeWindow < 0
                || diseaseTicks < 1 || contactCooldown < 0 || shortFuseTicks < 1) {
            throw new IllegalArgumentException("rules: ability and disease values out of range");
        }
        // One tick of movement at full speed must stay inside the hitbox, otherwise walls and fire could be skipped.
        long perTick = (long) speed / 2 * Simulation.SUB / (Simulation.TICKS_PER_SECOND * 1000L);
        long maxPerTick = (long) (speed + speedStep * maxSpeedUps) * Simulation.SUB / (Simulation.TICKS_PER_SECOND * 1000L);
        if (perTick < 1 || maxPerTick >= hitbox) {
            throw new IllegalArgumentException("speed: one tick, also when slowed to half, must move 1.." + (hitbox - 1) + " units");
        }
    }

    public int maxSpeed() { return speed + speedStep * maxSpeedUps; }

    /** A copy with one component replaced, e.g. {@code with("killsToWin", 5)}; validated like any ruleset. */
    public RulesetDefinition with(String component, Object value) {
        var components = RulesetDefinition.class.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        boolean found = false;
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                found |= components[i].getName().equals(component);
                values[i] = components[i].getName().equals(component) ? value : components[i].getAccessor().invoke(this);
            }
            if (!found) throw new IllegalArgumentException("rules: no component " + component);
            return RulesetDefinition.class.getDeclaredConstructor(types).newInstance(values);
        } catch (InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
