package com.greeklongbow.plugin.listeners;

import com.greeklongbow.plugin.GreekLongbowPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

public class ApolloDrawListener implements Listener {

    private final GreekLongbowPlugin plugin;

    // PDC key stamped on the arrow entity so we can identify it in flight
    private final NamespacedKey APOLLO_ARROW_KEY;
    // PDC key on the player to persist cooldown across restarts
    private final NamespacedKey COOLDOWN_PDC_KEY;

    private static final long   COOLDOWN_MS           = 35_000L;
    private static final long   BOSSBAR_TICK_INTERVAL = 20L;
    // Apollo's Draw fixed damage: 3 hearts = 6 half-hearts
    private static final double APOLLO_DAMAGE         = 6.0;
    // How far ahead to ray-trace for a lock-on target on activation (blocks)
    private static final double LOCK_ON_RANGE         = 48.0;
    // How aggressively the arrow steers (0.0–1.0; higher = snappier turn)
    private static final double HOMING_STRENGTH       = 0.35;
    // Arrow speed multiplier applied each tick to maintain velocity
    private static final double ARROW_SPEED           = 3.2;
    // Hit detection threshold: must be > ARROW_SPEED so the arrow cannot travel
    // past the target in a single tick without triggering the close-range check.
    // At 3.2 blocks/tick, anything under ~3.5 can overshoot. We use 4.0 to be safe.
    private static final double HIT_THRESHOLD         = 4.0;
    // Homing tracking interval in ticks
    private static final long   HOMING_TICK           = 1L;
    // Max homing duration in ticks before the arrow gives up and flies straight
    private static final int    MAX_HOMING_TICKS      = 120; // 6 seconds

    // Per-player last bossbar color bracket, so we only send .color() on change
    private final Map<UUID, BossBar.Color> lastBarColor = new HashMap<>();

    // Per-player state
    private final Map<UUID, Long>       cooldownExpiry  = new HashMap<>();
    private final Map<UUID, BossBar>    playerBossBars  = new HashMap<>();
    private final Map<UUID, BukkitTask> playerTasks     = new HashMap<>();
    // Arrow homing tasks: arrowUUID → task
    private final Map<UUID, BukkitTask> arrowTasks      = new HashMap<>();
    // Guards applyApolloHit so it fires at most once per arrow, regardless of
    // whether the homing task's threshold check or ProjectileHitEvent wins the race.
    private final Set<UUID>             arrowHitApplied = new HashSet<>();

    public ApolloDrawListener(GreekLongbowPlugin plugin) {
        this.plugin           = plugin;
        this.APOLLO_ARROW_KEY = new NamespacedKey(plugin, "apollo_arrow");
        this.COOLDOWN_PDC_KEY = new NamespacedKey(plugin, "greeklongbow_cooldown");
    }

    // -------------------------------------------------------------------------
    // Sneak + bow shoot activation — lock on and fire Apollo's Draw in one action
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!plugin.getGreekLongbowItem().isGreekLongbow(event.getBow())) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        // Ability only triggers when the player is sneaking while shooting
        if (!player.isSneaking()) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        long remaining = cooldownExpiry.getOrDefault(uuid, 0L) - now;

        if (remaining > 0) {
            long seconds = (remaining + 999) / 1000;
            player.sendActionBar(
                Component.text("\u2600 Apollo's Draw on cooldown \u2014 " + seconds + "s")
                    .color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            );
            return; // Fire a normal arrow — ability is on cooldown
        }

        // Ray-trace to find a lock-on target the player is looking at
        LivingEntity target = findLookTarget(player);

        if (target == null) {
            player.sendActionBar(
                Component.text("\u2600 No target in range. Apollo's Draw missed its mark.")
                    .color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            );
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            return; // Fire a normal arrow — nothing to lock onto
        }

        // Tag the arrow as an Apollo arrow
        arrow.getPersistentDataContainer().set(APOLLO_ARROW_KEY, PersistentDataType.BYTE, (byte) 1);

        // Start cooldown
        long newExpiry = now + COOLDOWN_MS;
        cooldownExpiry.put(uuid, newExpiry);
        player.getPersistentDataContainer().set(COOLDOWN_PDC_KEY, PersistentDataType.LONG, newExpiry);
        startBossBar(player);

        // Lock-on particles around the target
        target.getWorld().spawnParticle(Particle.DUST,
            target.getLocation().add(0, 1, 0), 30, 0.5, 0.7, 0.5, 0,
            new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 100, 255), 2.0f));
        target.getWorld().spawnParticle(Particle.ENCHANT,
            target.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 1.0);
        target.getWorld().playSound(target.getLocation(),
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.5f);

        // Begin homing
        startHoming(arrow, target, player);

        // Visual/audio feedback
        player.showTitle(Title.title(
            Component.text("\u2600 APOLLO'S DRAW")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true),
            Component.text("Arrow loosed \u2014 target locked!")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false),
            Title.Times.times(
                Duration.ofMillis(100),
                Duration.ofMillis(1100),
                Duration.ofMillis(400)
            )
        ));

        player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.7f, 1.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.5f);

        player.sendActionBar(
            Component.text("\u2600 Apollo's arrow is loosed!")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
        );
    }

    // -------------------------------------------------------------------------
    // Homing logic — runs every tick, aggressively steers toward the target
    // -------------------------------------------------------------------------

    private void startHoming(Arrow arrow, LivingEntity target, Player shooter) {
        UUID arrowUuid = arrow.getUniqueId();
        final int[] tickCount = {0};
        // Register in the shared set so onArrowLand can also check before calling
        // applyApolloHit, preventing a double-hit race between the two code paths.
        arrowHitApplied.remove(arrowUuid); // ensure clean state for this arrow

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tickCount[0]++;

            // Arrow gone or hit something
            if (!arrow.isValid() || arrow.isOnGround()) {
                cancelArrowTask(arrowUuid);
                return;
            }

            // Max homing duration exceeded — fly straight from here
            if (tickCount[0] > MAX_HOMING_TICKS) {
                cancelArrowTask(arrowUuid);
                return;
            }

            // Target dead, removed, or in a different world — also guard self-hit
            if (!target.isValid() || target.isDead()
                    || (shooter != null && target.getUniqueId().equals(shooter.getUniqueId()))) {
                cancelArrowTask(arrowUuid);
                return;
            }

            Location arrowLoc  = arrow.getLocation();
            Location targetLoc = target.getLocation().add(0, target.getHeight() * 0.6, 0);

            // Direction from arrow to target
            Vector toTarget = targetLoc.toVector().subtract(arrowLoc.toVector());
            double dist = toTarget.length();

            // If we're very close — force an immediate hit
            if (dist < HIT_THRESHOLD) {
                cancelArrowTask(arrowUuid);
                arrow.remove();
                // arrowHitApplied.add returns false if the UUID was already present,
                // meaning onArrowLand already fired — skip to avoid double damage.
                if (arrowHitApplied.add(arrowUuid)) {
                    applyApolloHit(target, shooter, arrowLoc);
                }
                return;
            }

            toTarget.normalize();

            // Blend current velocity with the target direction (LERP)
            Vector current = arrow.getVelocity().normalize();
            Vector newDir  = current.multiply(1.0 - HOMING_STRENGTH)
                                    .add(toTarget.multiply(HOMING_STRENGTH))
                                    .normalize()
                                    .multiply(ARROW_SPEED);

            arrow.setVelocity(newDir);

            // Purple/gold particle trail
            arrow.getWorld().spawnParticle(Particle.DUST, arrowLoc, 6, 0.05, 0.05, 0.05, 0,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 100, 255), 1.8f));
            arrow.getWorld().spawnParticle(Particle.ENCHANT, arrowLoc, 3, 0.1, 0.1, 0.1, 0.3);

        }, 1L, HOMING_TICK);

        arrowTasks.put(arrowUuid, task);
    }

    private void applyApolloHit(LivingEntity target, Player shooter, Location hitLoc) {
        // Reset the target's invincibility frames so the damage always registers,
        // even if the entity was just hit by something else a moment ago.
        int savedNoDamageTicks = target.getNoDamageTicks();
        target.setNoDamageTicks(0);
        // Note: we do NOT tag the damage source with APOLLO_ARROW_KEY here, so
        // onApolloArrowDamage will NOT intercept and cancel this damage call.
        // This is intentional — applyApolloHit is called after the arrow entity
        // has already been removed or after we've cancelled the arrow task, meaning
        // the EntityDamageByEntityEvent fired by target.damage() comes from the
        // shooter Player directly, not from the Arrow entity.
        target.damage(APOLLO_DAMAGE, shooter);
        target.setNoDamageTicks(savedNoDamageTicks);

        target.getWorld().spawnParticle(Particle.EXPLOSION, hitLoc, 1);
        target.getWorld().spawnParticle(Particle.DUST,
            hitLoc.clone().add(0, 1, 0), 40, 0.5, 0.7, 0.5, 0,
            new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 100, 255), 3.0f));
        target.getWorld().spawnParticle(Particle.ENCHANT,
            hitLoc.clone().add(0, 1, 0), 25, 0.4, 0.7, 0.4, 1.0);
        target.getWorld().playSound(hitLoc, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 0.8f);
        target.getWorld().playSound(hitLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 1.9f);

        shooter.sendActionBar(
            Component.text("\u2600 Apollo's arrow found its mark!")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
        );
    }

    // -------------------------------------------------------------------------
    // Cancel vanilla bow damage AND knockback from the Apollo arrow — we deal
    // our own fixed damage via applyApolloHit. Without this, the arrow landing
    // on an entity naturally would deal Power V bow damage ON TOP of our 6.0
    // ability damage. Cancelling EntityDamageByEntityEvent on Paper 1.21.11 also
    // suppresses the Punch III knockback that the Arrow entity carries.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onApolloArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!arrow.getPersistentDataContainer().has(APOLLO_ARROW_KEY, PersistentDataType.BYTE)) return;
        // Suppress vanilla damage and Punch III knockback — applyApolloHit handles damage.
        // Also zero out the knockback multiplier to be safe across Paper versions.
        event.setCancelled(true);
        if (event.getEntity() instanceof LivingEntity le) {
            // Belt-and-suspenders: reset any residual velocity added by the arrow
            // before this event fired (Paper applies knockback before the event on some builds).
            le.setVelocity(le.getVelocity().multiply(0));
        }
    }

    // -------------------------------------------------------------------------
    // When the homing arrow hits something:
    //  - Block hit: cancel task, spawn particles.
    //  - Entity hit: vanilla collision beat the homing task's threshold check.
    //    Cancel the task and apply Apollo damage here as the fallback.
    //    EntityDamageByEntityEvent (which fires after this) is already cancelled
    //    by onApolloArrowDamage, so no double-damage occurs.
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.getPersistentDataContainer().has(APOLLO_ARROW_KEY, PersistentDataType.BYTE)) return;

        cancelArrowTask(arrow.getUniqueId());

        Location loc = arrow.getLocation();

        if (event.getHitEntity() instanceof LivingEntity hitEntity) {
            // Vanilla collision fired before the homing task's threshold check.
            // Guard with arrowHitApplied so only one code path deals damage.
            // onApolloArrowDamage will still cancel the vanilla EntityDamageByEntityEvent.
            Player shooter = (arrow.getShooter() instanceof Player p) ? p : null;
            if (arrowHitApplied.add(arrow.getUniqueId())) {
                applyApolloHit(hitEntity, shooter, loc);
            }
            return;
        }

        // Block hit — just do the miss particles
        arrow.getWorld().spawnParticle(Particle.DUST, loc, 25, 0.3, 0.3, 0.3, 0,
            new Particle.DustOptions(org.bukkit.Color.fromRGB(180, 80, 220), 1.8f));
        arrow.getWorld().spawnParticle(Particle.ENCHANT, loc, 12, 0.2, 0.2, 0.2, 0.5);
        arrow.getWorld().playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.5f, 1.6f);
    }

    // -------------------------------------------------------------------------
    // Ray-trace helper — find the living entity the player is looking at
    // -------------------------------------------------------------------------

    private LivingEntity findLookTarget(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            LOCK_ON_RANGE,
            entity -> entity instanceof LivingEntity
                   && entity.getEntityId() != player.getEntityId()
        );

        if (result == null || !(result.getHitEntity() instanceof LivingEntity le)) return null;
        return le;
    }

    private LivingEntity resolveTarget(UUID targetUuid, Player shooter) {
        // O(1) direct UUID lookup — no world/entity iteration needed
        org.bukkit.entity.Entity e = plugin.getServer().getEntity(targetUuid);
        if (e instanceof LivingEntity le) return le;
        return null;
    }

    private void cancelArrowTask(UUID arrowUuid) {
        BukkitTask t = arrowTasks.remove(arrowUuid);
        if (t != null && !t.isCancelled()) t.cancel();
        arrowHitApplied.remove(arrowUuid);
    }

    // -------------------------------------------------------------------------
    // Boss bar — cooldown display
    // -------------------------------------------------------------------------

    private BossBar getOrCreateBossBar(UUID uuid) {
        return playerBossBars.computeIfAbsent(uuid, k -> BossBar.bossBar(
            Component.text("\u2600 Apollo's Draw Cooldown")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true),
            1.0f,
            BossBar.Color.PURPLE,
            BossBar.Overlay.NOTCHED_10
        ));
    }

    private void startBossBar(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask old = playerTasks.remove(uuid);
        if (old != null && !old.isCancelled()) old.cancel();

        BossBar bar = getOrCreateBossBar(uuid);
        player.showBossBar(bar);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now       = System.currentTimeMillis();
            long expiry    = cooldownExpiry.getOrDefault(uuid, 0L);
            long remaining = expiry - now;

            if (remaining <= 0) {
                player.hideBossBar(bar);
                BukkitTask self = playerTasks.remove(uuid);
                if (self != null) self.cancel();

                player.sendActionBar(
                    Component.text("\u2600 Apollo's Draw ready!")
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                );
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                return;
            }

            long seconds   = (remaining + 999) / 1000;
            float progress = Math.min(1.0f, (float) remaining / COOLDOWN_MS);

            bar.name(
                Component.text("\u2600 Apollo's Draw \u2014 " + seconds + "s")
                    .color(NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.BOLD, true)
            );
            bar.progress(progress);
            BossBar.Color targetColor = progress > 0.4f ? BossBar.Color.PURPLE : BossBar.Color.RED;
            if (targetColor != lastBarColor.get(uuid)) {
                bar.color(targetColor);
                lastBarColor.put(uuid, targetColor);
            }

        }, 1L, BOSSBAR_TICK_INTERVAL);

        playerTasks.put(uuid, task);
    }

    private void hideBossBar(Player player) {
        UUID uuid = player.getUniqueId();
        cooldownExpiry.remove(uuid);
        lastBarColor.remove(uuid);
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) player.hideBossBar(bar);
        BukkitTask task = playerTasks.remove(uuid);
        if (task != null && !task.isCancelled()) task.cancel();
    }

    // -------------------------------------------------------------------------
    // Join / Quit
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Restore any persisted cooldown for this player, regardless of current owner status.
        // If ownership was transferred, the old owner's cooldown clears naturally (expiry is past);
        // the new owner gets their cooldown loaded correctly.
        long saved = player.getPersistentDataContainer()
            .getOrDefault(COOLDOWN_PDC_KEY, PersistentDataType.LONG, 0L);

        if (saved > System.currentTimeMillis()) {
            cooldownExpiry.put(uuid, saved);
            startBossBar(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideBossBar(event.getPlayer());
    }

    // -------------------------------------------------------------------------
    // Cleanup on plugin disable
    // -------------------------------------------------------------------------

    public void cleanupAll() {
        List<UUID> uuids = new ArrayList<>(playerBossBars.keySet());
        for (UUID uuid : uuids) {
            Player online = plugin.getServer().getPlayer(uuid);
            BossBar bar = playerBossBars.remove(uuid);
            if (bar != null && online != null) online.hideBossBar(bar);
            BukkitTask task = playerTasks.remove(uuid);
            if (task != null && !task.isCancelled()) task.cancel();
        }
        for (BukkitTask task : arrowTasks.values()) {
            if (!task.isCancelled()) task.cancel();
        }
        arrowTasks.clear();
        playerTasks.clear();
        cooldownExpiry.clear();
        lastBarColor.clear();
        arrowHitApplied.clear();
    }
}
