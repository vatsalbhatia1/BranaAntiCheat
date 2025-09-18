package me.brana.anticheat;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BranaAntiCheatPlugin extends JavaPlugin implements Listener {

    private File offensesFile;
    private FileConfiguration offensesConfig;
    private Map<UUID, Map<String, Integer>> offenses = new ConcurrentHashMap<>();

    private Map<UUID, Long> lastHitTime = new ConcurrentHashMap<>();
    private Map<UUID, Integer> hitBurst = new ConcurrentHashMap<>();
    private Map<UUID, Double> lastY = new HashMap<>();
    private Map<UUID, Long> lastGroundTime = new HashMap<>();
    private Map<UUID, Long> lastFallDamageTime = new HashMap<>();

    private final List<String> minorHacks = Arrays.asList("jesus", "scaffolding", "speed", "step");
    private final List<String> majorHacks = Arrays.asList("fly", "reach", "aimassist", "nofall");

    @Override
    public void onEnable() {
        getLogger().info("BranaAntiCheat enabled!");
        loadOffensesFile();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveOffensesFile, 20L * 60, 20L * 60);
    }

    @Override
    public void onDisable() {
        saveOffensesFile();
        getLogger().info("BranaAntiCheat disabled!");
    }

    private void loadOffensesFile() {
        offensesFile = new File(getDataFolder(), "offenses.yml");
        if (!offensesFile.getParentFile().exists()) offensesFile.getParentFile().mkdirs();
        if (!offensesFile.exists()) {
            try { offensesFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        offensesConfig = YamlConfiguration.loadConfiguration(offensesFile);
        for (String key : offensesConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Map<String, Integer> map = new HashMap<>();
                for (String cheat : offensesConfig.getConfigurationSection(key).getKeys(false)) {
                    map.put(cheat, offensesConfig.getInt(key + "." + cheat));
                }
                offenses.put(uuid, map);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private synchronized void saveOffensesFile() {
        try {
            for (Map.Entry<UUID, Map<String, Integer>> e : offenses.entrySet()) {
                String key = e.getKey().toString();
                for (Map.Entry<String, Integer> m : e.getValue().entrySet()) {
                    offensesConfig.set(key + "." + m.getKey(), m.getValue());
                }
            }
            offensesConfig.save(offensesFile);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent ev) {
        if (!(ev.getDamager() instanceof Player)) return;
        Player attacker = (Player) ev.getDamager();
        Entity target = ev.getEntity();

        if (attacker.hasPermission("anticheat.bypass")) return;
        if (attacker.getGameMode() == GameMode.CREATIVE) return;

        // Reach detection
        double distance = attacker.getLocation().distance(target.getLocation());
        if (distance > 4.5) handleHack(attacker, "reach");

        // Aim assist detection (burst hits)
        long now = System.currentTimeMillis();
        long last = lastHitTime.getOrDefault(attacker.getUniqueId(), 0L);
        int burst = hitBurst.getOrDefault(attacker.getUniqueId(), 0);
        if (now - last <= 1000) burst++;
        else burst = 1;
        lastHitTime.put(attacker.getUniqueId(), now);
        hitBurst.put(attacker.getUniqueId(), burst);
        if (burst >= 12) handleHack(attacker, "aimassist");
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent ev) {
        Player p = ev.getPlayer();
        if (p.hasPermission("anticheat.bypass")) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        UUID id = p.getUniqueId();
        Location to = ev.getTo();
        if (to == null) return;

        double prevY = lastY.getOrDefault(id, ev.getFrom().getY());
        double dy = prevY - to.getY();
        lastY.put(id, to.getY());

        // Fly detection
        if (!p.isOnGround()) {
            long since = System.currentTimeMillis() - lastGroundTime.getOrDefault(id, System.currentTimeMillis());
            if (since > 6000 && Math.abs(dy) < 0.6 && !p.isInsideVehicle()) handleHack(p, "fly");
        } else lastGroundTime.put(id, System.currentTimeMillis());

        // Jesus detection
        Material below = p.getLocation().clone().add(0, -1, 0).getBlock().getType();
        Material current = p.getLocation().getBlock().getType();
        if (below == Material.WATER && current != Material.WATER && !p.isInWater() && !p.isInsideVehicle()) handleHack(p, "jesus");

        // No-fall detection
        if (dy > 3.0) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Long t = lastFallDamageTime.get(id);
                if (t == null || (t < System.currentTimeMillis() - 5000)) handleHack(p, "nofall");
            }, 60L);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent ev) {
        if (!(ev.getEntity() instanceof Player)) return;
        if (ev.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Player p = (Player) ev.getEntity();
            lastFallDamageTime.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void handleHack(Player p, String hackType) {
        Bukkit.broadcastMessage(ChatColor.RED + p.getName() + " is doing " + hackType);
        incrementOffenseAndPunish(p, hackType.toLowerCase());
    }

    private void incrementOffenseAndPunish(Player p, String hackType) {
        UUID id = p.getUniqueId();
        Map<String, Integer> map = offenses.computeIfAbsent(id, k -> new HashMap<>());
        int newCount = map.getOrDefault(hackType, 0) + 1;
        map.put(hackType, newCount);
        Bukkit.getScheduler().runTaskAsynchronously(this, this::saveOffensesFile);

        if (minorHacks.contains(hackType)) {
            switch (newCount) {
                case 1 -> kickPlayer(p, hackType);
                case 2 -> banPlayer(p.getName(), hackType, 1);
                case 3 -> banPlayer(p.getName(), hackType, 5);
                default -> permBanPlayer(p.getName(), hackType);
            }
        } else if (majorHacks.contains(hackType)) {
            switch (newCount) {
                case 1 -> banPlayer(p.getName(), hackType, 1);
                case 2 -> banPlayer(p.getName(), hackType, 5);
                default -> permBanPlayer(p.getName(), hackType);
            }
        } else kickPlayer(p, hackType); // fallback
    }

    private void kickPlayer(Player p, String hackType) {
        p.kickPlayer(ChatColor.RED + "Kicked for using " + hackType + " hack!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "[BranaAntiCheat] " + p.getName() + " was kicked for " + hackType + "!");
    }

    private void banPlayer(String playerName, String reason, int days) {
        Date expires = Date.from(Instant.now().plus(days, ChronoUnit.DAYS));
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, expires, "BranaAntiCheat");
        Player p = Bukkit.getPlayerExact(playerName);
        if (p != null) p.kickPlayer(ChatColor.RED + "Banned for " + days + " days: " + reason);
    }

    private void permBanPlayer(String playerName, String reason) {
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, null, "BranaAntiCheat");
        Player p = Bukkit.getPlayerExact(playerName);
        if (p != null) p.kickPlayer(ChatColor.RED + "Permanently banned: " + reason);
    }
}
