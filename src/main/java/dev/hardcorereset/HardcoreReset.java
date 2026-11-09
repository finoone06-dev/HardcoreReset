package dev.hardcorereset;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * HardcoreReset: when anyone dies on a hardcore server, the world is wiped and regenerated.
 *
 * How it works: Minecraft opens the world before plugins load, so the folder cannot be deleted
 * safely while the server runs. Instead, on reset the plugin flips "level-name" in
 * server.properties (world -> world_hc2 -> world -> ...), records the old name, and restarts.
 * The server then generates a brand-new world (new seed) and, on that boot, this plugin deletes
 * the old world folders.
 */
public final class HardcoreReset extends JavaPlugin implements Listener {

    private static final String SUFFIX = "_hc2";
    private static final String PROPERTIES_FILE = "server.properties";

    private final AtomicBoolean resetting = new AtomicBoolean(false);
    private volatile boolean committed = false;
    private BukkitTask countdownTask;
    private String deathCause = "someone";
    private File cleanupFile;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onLoad() {
        cleanupFile = new File(getDataFolder(), "pending-cleanup.txt");
        runPendingCleanup();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("require-hardcore", true) && !getServer().isHardcore()) {
            getLogger().warning("This server is not in hardcore mode (hardcore=true in server.properties). "
                    + "HardcoreReset will do nothing until it is, or set require-hardcore: false in config.yml.");
        }
    }

    // ------------------------------------------------------------------ events & commands

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (getConfig().getBoolean("require-hardcore", true) && !getServer().isHardcore()) {
            return;
        }
        startReset(event.getEntity().getName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hardcorereset.admin")) {
            sender.sendMessage(Component.text("You do not have permission."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "reset" -> {
                if (!startReset(sender.getName() + " (manual reset)")) {
                    sender.sendMessage(Component.text("A reset is already in progress."));
                }
            }
            case "cancel" -> {
                if (resetting.get() && !committed) {
                    cancelReset();
                } else {
                    sender.sendMessage(Component.text("There is no reset countdown to cancel."));
                }
            }
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <reset|cancel>"));
        }
        return true;
    }

    // ------------------------------------------------------------------ reset flow

    private boolean startReset(String cause) {
        if (!resetting.compareAndSet(false, true)) {
            return false;
        }
        committed = false;
        deathCause = cause;
        final int seconds = Math.max(0, getConfig().getInt("countdown-seconds", 10));
        getServer().broadcast(message("messages.death", seconds));

        if (seconds == 0) {
            getServer().getScheduler().runTask(this, this::performReset);
            return true;
        }

        final int[] left = {seconds};
        countdownTask = getServer().getScheduler().runTaskTimer(this, () -> {
            left[0]--;
            if (left[0] <= 0) {
                countdownTask.cancel();
                performReset();
            } else if (left[0] <= 5 || left[0] % 5 == 0) {
                getServer().broadcast(message("messages.countdown", left[0]));
            }
        }, 20L, 20L);
        return true;
    }

    private void cancelReset() {
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        resetting.set(false);
        getServer().broadcast(message("messages.cancelled", 0));
    }

    private void performReset() {
        committed = true;
        String current = readLevelName();
        String next = current.endsWith(SUFFIX)
                ? current.substring(0, current.length() - SUFFIX.length())
                : current + SUFFIX;

        if (!isSafeName(current) || !isSafeName(next)) {
            getLogger().severe("Refusing to reset: unsafe level-name '" + current + "' in " + PROPERTIES_FILE);
            fail();
            return;
        }

        try {
            getDataFolder().mkdirs();
            Files.writeString(cleanupFile.toPath(), current, StandardCharsets.UTF_8);
            updateServerProperties(next);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not update " + PROPERTIES_FILE, ex);
            cleanupFile.delete();
            fail();
            return;
        }

        getLogger().info("World reset: level-name '" + current + "' -> '" + next + "'. Restarting.");
        Component kickMessage = message("messages.kick", 0);
        for (Player player : new ArrayList<>(getServer().getOnlinePlayers())) {
            player.kick(kickMessage);
        }

        getServer().getScheduler().runTaskLater(this, () -> {
            String cmd = getConfig().getString("restart-command", "restart");
            boolean dispatched = false;
            try {
                dispatched = getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Restart command '" + cmd + "' failed", ex);
            }
            if (!dispatched) {
                getServer().shutdown();
            }
        }, 20L);
    }

    private void fail() {
        committed = false;
        resetting.set(false);
        getServer().broadcast(message("messages.failed", 0));
    }

    // ------------------------------------------------------------------ cleanup of the old world

    private void runPendingCleanup() {
        if (!cleanupFile.isFile()) {
            return;
        }
        try {
            String oldName = Files.readString(cleanupFile.toPath(), StandardCharsets.UTF_8).trim();
            String current = readLevelName();
            if (oldName.isEmpty() || oldName.equals(current) || !isSafeName(oldName)) {
                getLogger().warning("Skipping world cleanup (old='" + oldName + "', current='" + current + "').");
                cleanupFile.delete();
                return;
            }
            File container = getServer().getWorldContainer();
            boolean ok = true;
            for (String name : new String[] {oldName, oldName + "_nether", oldName + "_the_end"}) {
                ok &= deleteFolder(container, new File(container, name));
            }
            if (ok) {
                cleanupFile.delete();
                getLogger().info("Deleted old world '" + oldName + "'. Fresh world is '" + current + "'.");
            } else {
                getLogger().severe("Could not fully delete old world '" + oldName + "'; will retry next start.");
            }
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "World cleanup failed", ex);
        }
    }

    private boolean deleteFolder(File containerDir, File dir) {
        if (!dir.exists()) {
            return true;
        }
        final Path container = containerDir.toPath().toAbsolutePath().normalize();
        final Path root = dir.toPath().toAbsolutePath().normalize();
        if (!root.startsWith(container) || root.equals(container)) {
            getLogger().severe("Refusing to delete outside world container: " + root);
            return false;
        }
        final boolean[] ok = {true};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                    } catch (IOException ex) {
                        ok[0] = false;
                        getLogger().warning("Could not delete " + file + ": " + ex.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try {
                        Files.delete(d);
                    } catch (IOException ex) {
                        ok[0] = false;
                        getLogger().warning("Could not delete " + d + ": " + ex.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Error while deleting " + root, ex);
            return false;
        }
        return ok[0];
    }

    // ------------------------------------------------------------------ server.properties helpers

    private String readLevelName() {
        try {
            for (String line : Files.readAllLines(Path.of(PROPERTIES_FILE), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("level-name=")) {
                    String value = line.substring("level-name=".length()).trim();
                    return value.isEmpty() ? "world" : value;
                }
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not read " + PROPERTIES_FILE, ex);
        }
        return "world";
    }

    private void updateServerProperties(String newLevelName) throws IOException {
        Path file = Path.of(PROPERTIES_FILE);
        List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        boolean randomizeSeed = getConfig().getBoolean("randomize-seed", true);
        boolean foundName = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("level-name=")) {
                lines.set(i, "level-name=" + newLevelName);
                foundName = true;
            } else if (randomizeSeed && line.startsWith("level-seed=")) {
                lines.set(i, "level-seed=");
            }
        }
        if (!foundName) {
            lines.add("level-name=" + newLevelName);
        }
        Path tmp = Path.of(PROPERTIES_FILE + ".hcr-tmp");
        Files.write(tmp, lines, StandardCharsets.ISO_8859_1);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isSafeName(String name) {
        return name.matches("[A-Za-z0-9._-]+") && !name.equals(".") && !name.contains("..");
    }

    // ------------------------------------------------------------------ messages

    private Component message(String key, int seconds) {
        String raw = getConfig().getString(key, key);
        raw = raw.replace("%player%", deathCause).replace("%seconds%", Integer.toString(seconds));
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
