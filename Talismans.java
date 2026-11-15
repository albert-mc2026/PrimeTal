package kg.primetal;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Talismans (work while in the inventory) and spheres (work in the off hand).
 * Everything - names, materials, effects, special abilities - lives in config.yml.
 */
final class Talismans implements Listener, TabExecutor {

    static Talismans instance;

    private static final class Eff {
        final PotionEffectType type;
        final int amp;
        Eff(PotionEffectType type, int amp) { this.type = type; this.amp = amp; }
    }

    private static final class Def {
        final String kind;      // "talisman" or "sphere"
        final String id;
        final String name;
        final Material material;
        final List<String> lore;
        final List<Eff> effects;
        final Map<String, Double> specials;

        Def(String kind, String id, String name, Material material, List<String> lore,
            List<Eff> effects, Map<String, Double> specials) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.material = material;
            this.lore = lore;
            this.effects = effects;
            this.specials = specials;
        }

        String key() { return kind + ":" + id; }
    }

    private final PrimeTal plugin;
    private final NamespacedKey tagKey;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Long> phoenixUsed = new HashMap<>();

    Talismans(PrimeTal plugin) {
        this.plugin = plugin;
        this.tagKey = Objects.requireNonNull(NamespacedKey.fromString("anarchykit:magic_item"));
        instance = this;
        load();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ------------------------------------------------------------ config

    @SuppressWarnings("deprecation")
    private static PotionEffectType effect(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        PotionEffectType t = null;
        try {
            t = PotionEffectType.getByKey(NamespacedKey.minecraft(n));
        } catch (RuntimeException ignored) { }
        if (t == null) t = PotionEffectType.getByName(n.toUpperCase(Locale.ROOT));
        return t;
    }

    void load() {
        defs.clear();
        for (String kind : new String[]{"talisman", "sphere"}) {
            String root = kind.equals("talisman") ? "talismans" : "spheres";
            ConfigurationSection items = plugin.getConfig().getConfigurationSection(root + ".items");
            if (items == null) continue;
            for (String id : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(id);
                if (s == null) continue;

                Material m = Material.matchMaterial(s.getString("material", "NETHER_STAR"));
                if (m == null || m.isAir() || !m.isItem()) {
                    plugin.getLogger().warning("Bad material for " + kind + " '" + id + "'");
                    continue;
                }

                List<Eff> effs = new ArrayList<>();
                for (String line : s.getStringList("effects")) {
                    String[] p = line.split(":");
                    PotionEffectType t = effect(p[0]);
                    if (t == null) {
                        plugin.getLogger().warning("Unknown effect '" + p[0] + "' in " + kind + " '" + id + "'");
                        continue;
                    }
                    int amp = 0;
                    if (p.length > 1) {
                        try { amp = Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { }
                    }
                    effs.add(new Eff(t, amp));
                }

                Map<String, Double> specials = new HashMap<>();
                ConfigurationSection sp = s.getConfigurationSection("specials");
                if (sp != null) {
                    for (String k : sp.getKeys(false)) specials.put(k.toLowerCase(Locale.ROOT), sp.getDouble(k));
                }

                List<String> lore = new ArrayList<>();
                for (String l : s.getStringList("lore")) lore.add(PrimeTal.c(l));
                lore.add(ChatColor.DARK_GRAY + (kind.equals("talisman")
                        ? "Талисман: инвентарда иштейт"
                        : "Сфера: экинчи колдо иштейт"));

                Def d = new Def(kind, id.toLowerCase(Locale.ROOT), PrimeTal.c(s.getString("name", id)), m, lore, effs, specials);
                defs.put(d.key(), d);
            }
        }
        plugin.getLogger().info("Loaded " + defs.size() + " talismans/spheres.");
    }

    // ------------------------------------------------------------ items

    private ItemStack create(Def d, int amount) {
        ItemStack it = new ItemStack(d.material, amount);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(d.name);
        m.setLore(new ArrayList<>(d.lore));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, d.key());
        it.setItemMeta(m);
        return it;
    }

    private Def defOf(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        String v = it.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
        return v == null ? null : defs.get(v);
    }

    /** Talismans anywhere in the inventory (or off hand only, see config) + the sphere in the off hand. */
    private List<Def> active(Player p) {
        Map<String, Def> out = new LinkedHashMap<>();
        PlayerInventory inv = p.getInventory();
        boolean invMode = !"offhand".equalsIgnoreCase(plugin.getConfig().getString("talismans.mode", "inventory"));
        if (invMode) {
            for (ItemStack it : inv.getContents()) {
                Def d = defOf(it);
                if (d != null && d.kind.equals("talisman")) out.putIfAbsent(d.key(), d);
            }
        }
        Def off = defOf(inv.getItemInOffHand());
        if (off != null && (off.kind.equals("sphere") || !invMode)) out.putIfAbsent(off.key(), off);
        return new ArrayList<>(out.values());
    }

    private static double spec(List<Def> act, String key) {
        double best = 0;
        for (Def d : act) {
            Double v = d.specials.get(key);
            if (v != null && v > best) best = v;
        }
        return best;
    }

    // ------------------------------------------------------------ passive effects

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Def d : active(p)) {
                for (Eff e : d.effects) {
                    int dur = e.type.getKey().getKey().equals("night_vision") ? 260 : 80;
                    p.addPotionEffect(new PotionEffect(e.type, dur, e.amp, true, false, true));
                }
            }
        }
    }

    // ------------------------------------------------------------ special abilities

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        List<Def> act = active(p);
        if (act.isEmpty()) return;

        double ignite = spec(act, "ignite");
        if (ignite > 0) t.setFireTicks((int) ignite);

        double slow = spec(act, "slow");
        PotionEffectType slowness = effect("slowness");
        if (slow > 0 && slowness != null) t.addPotionEffect(new PotionEffect(slowness, (int) (slow * 20), 1));

        double blind = spec(act, "blind");
        PotionEffectType blindness = effect("blindness");
        if (blind > 0 && blindness != null) t.addPotionEffect(new PotionEffect(blindness, (int) (blind * 20), 0));

        double vamp = spec(act, "vampire");
        if (vamp > 0) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            double max = a == null ? 20.0 : a.getValue();
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * vamp));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        List<Def> act = active(p);
        if (act.isEmpty()) return;

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && spec(act, "nofall") > 0) {
            e.setCancelled(true);
            return;
        }

        double cooldown = spec(act, "phoenix");
        if (cooldown > 0 && p.getHealth() - e.getFinalDamage() <= 0) {
            long now = System.currentTimeMillis();
            Long last = phoenixUsed.get(p.getUniqueId());
            if (last != null && now - last < cooldown * 1000) return;
            phoenixUsed.put(p.getUniqueId(), now);

            e.setCancelled(true);
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            double max = a == null ? 20.0 : a.getValue();
            p.setHealth(Math.min(max, 8.0));
            PotionEffectType regen = effect("regeneration");
            if (regen != null) p.addPotionEffect(new PotionEffect(regen, 100, 1));
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.2);
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
            p.sendMessage(ChatColor.GOLD + "Феникс сизди өлүмдөн сактады!");
        }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String kind = cmd.getName().equalsIgnoreCase("sphere") ? "sphere" : "talisman";
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            s.sendMessage(ChatColor.GOLD + (kind.equals("sphere") ? "Сфералар:" : "Талисмандар:"));
            for (Def d : defs.values()) {
                if (d.kind.equals(kind)) s.sendMessage(ChatColor.YELLOW + d.id + ChatColor.GRAY + " - " + d.name);
            }
            return true;
        }

        if (!s.hasPermission("primetal.admin")) {
            s.sendMessage(ChatColor.RED + "Укугуңуз жок.");
            return true;
        }

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            load();
            s.sendMessage(ChatColor.GREEN + "Талисман/сфера конфиги кайра жүктөлдү.");
            return true;
        }

        if (sub.equals("give") && args.length >= 3) {
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) { s.sendMessage(ChatColor.RED + "Оюнчу онлайн эмес."); return true; }
            Def d = defs.get(kind + ":" + args[2].toLowerCase(Locale.ROOT));
            if (d == null) { s.sendMessage(ChatColor.RED + "Мындай жок. /" + kind + " list"); return true; }
            int amount = 1;
            if (args.length >= 4) {
                try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); } catch (NumberFormatException ignored) { }
            }
            for (ItemStack left : t.getInventory().addItem(create(d, amount)).values()) {
                t.getWorld().dropItemNaturally(t.getLocation(), left);
            }
            s.sendMessage(ChatColor.GREEN + t.getName() + " оюнчусуна берилди: " + d.name + ChatColor.GREEN + " x" + amount);
            return true;
        }

        s.sendMessage(ChatColor.YELLOW + "/" + kind + " list | give <оюнчу> <id> [сан] | reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        String kind = cmd.getName().equalsIgnoreCase("sphere") ? "sphere" : "talisman";
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("list");
            if (s.hasPermission("primetal.admin")) {
                out.add("give");
                out.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Def d : defs.values()) if (d.kind.equals(kind)) out.add(d.id);
        }
        String pre = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }
}
