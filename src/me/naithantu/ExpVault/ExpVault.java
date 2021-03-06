package me.naithantu.ExpVault;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExpVault extends JavaPlugin implements Listener {

	String header = ChatColor.BLUE + "[ExpVault]" + ChatColor.WHITE + " ";
	IDs ids;

	Economy econ;
	Logger logger;

	Configuration config;
	IDStorage idStorage;
	Transfer transfer;

	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
		logger = getLogger();
		File configFile = new File(getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			logger.log(Level.INFO, "No config found, generating default config.");
			this.saveDefaultConfig();
		}

		config = getConfig();
		idStorage = new IDStorage(this);
		ids = new IDs(this, idStorage);
		ids.loadIDS();
		transfer = new Transfer(config, header, ids);

		if (!setupEconomy()) {
			getConfig().set("economy.enable", false);
			logger.log(Level.SEVERE, "Vault not found! Economy disabled!");
		}
	}

	public void onDisable() {
		ids.saveIDS();
	}

	private boolean setupEconomy() {
		if (getConfig().getBoolean("economy.enable")) {
			if (getServer().getPluginManager().getPlugin("Vault") == null) {
				return false;
			}
			RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
			if (rsp == null) {
				return false;
			}
			econ = rsp.getProvider();
			if (econ == null) {
				return false;
			}
		}
		return true;
	}

	public void sendMessage(CommandSender sender, String message) {
		sender.sendMessage(header + message);
	}

	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		if (cmd.getName().equalsIgnoreCase("expvault")) {
			if (args.length == 0) {
				sendMessage(sender, "/expvault reload");
			}
			if (args.length > 0) {
				if (args[0].equalsIgnoreCase("reload")) {
					reloadConfig();
					HandlerList.unregisterAll((JavaPlugin) this);
					getServer().getPluginManager().disablePlugin(this);
					getServer().getPluginManager().enablePlugin(this);
					sendMessage(sender, "Plugin reloaded!");
				} else {
					sendMessage(sender, "/expvault reload");
				}
			}
			return true;
		}

        //Command to check the ID
        if (cmd.getName().equals("checkid")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "You need to be in-game to do that!");
            } else {
                sendMessage(sender, "Your ID is: " + ids.getPlayerID((Player) sender));
            }
            return true;
        }
		return false;
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent event) {
		if (!(event.getBlock().getState() instanceof Sign))
			return;

		Player player = event.getPlayer();
		Sign sign = (Sign) event.getBlock().getState();
		if (!sign.getLine(0).equals(header)) {
            return;
        }

        //Check if the owner or override
        Integer id = ids.getPlayerID(player);
        int foundID = Integer.valueOf(sign.getLine(3));
        if ((id == null || id != foundID) && !player.hasPermission("expvault.override")) {
            event.setCancelled(true);
            return;
        }

        //Get the Exp
        String expLine = sign.getLine(2);
        int exp = Integer.parseInt(expLine.replace("Exp: ", ""));
        player.giveExp(exp);
        sendMessage(player, "You have collected the experience stored in the ExpVault.");
        if (config.getBoolean("economy.enable")) {
            if (config.getInt("economy.return") == 0)
                return;
            econ.depositPlayer(player, config.getInt("economy.return"));
            sendMessage(player, "You have been returned " + config.getInt("economy.return") + " for removing your ExpVault.");
        }
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getClickedBlock() == null)
			return;

		Player player = event.getPlayer();
		if (event.getClickedBlock().getType() == Material.SIGN_POST|| event.getClickedBlock().getType() == Material.WALL_SIGN) {
			Sign sign = (Sign) event.getClickedBlock().getState();
            //Check if it is an expvault.
			if (!sign.getLine(0).equalsIgnoreCase(header)) {
                //If not an expvault, check if it is an expbank that can be converted.
				transfer.handleSignInteract(event);
				return;
			}
            //Check permission
            if (!player.hasPermission("expvault.create")) {
                player.sendMessage(ChatColor.RED + "You do not have permission for that!");
                return;
            }
            //Check if the expvault belongs to the player
			if (Integer.parseInt(sign.getLine(3)) != ids.getPlayerID(player)) {
				sendMessage(player, "That is not your ExpVault!");
				return;
			}

			if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
				addToSign(event, player);
			} else if (event.getAction() == Action.RIGHT_CLICK_BLOCK)
				subtractFromSign(event, player);

		}
	}

	private void subtractFromSign(PlayerInteractEvent event, Player player) {
        //Get the Exp
		Sign sign = (Sign) event.getClickedBlock().getState();
		String expLine = sign.getLine(2);
        int exp = Integer.parseInt(expLine.replace("Exp: ", ""));

        //Update the name (incase of a name change)
        sign.setLine(1, player.getName());

        //Check if any XP in the vault
		if (exp <= 0) {
            sign.update();
			sendMessage(player, "That ExpVault is empty!");
			return;
		}

        //Convert to levels
		int level = player.getLevel();
		int levelsToWithdraw = 0;
		if (player.isSneaking()) {
			levelsToWithdraw = config.getInt("experience.withdrawShift");
		} else {
			levelsToWithdraw = config.getInt("experience.withdraw");
		}
		int xpToWithdraw = ExpUtil.getXpFrom(level + levelsToWithdraw, player.getExp(), levelsToWithdraw);

		if (exp >= xpToWithdraw) {
			sign.setLine(2, "Exp: " + Integer.toString(exp - xpToWithdraw));
			sign.update();
			player.giveExp(xpToWithdraw);
			if (levelsToWithdraw == 1) {
				sendMessage(player, "You took " + levelsToWithdraw + " level.");
				return;
			}
			sendMessage(player, "You took " + levelsToWithdraw + " levels.");
		} else {
			sign.setLine(2, "Exp: 0");
			sign.update();
			player.giveExp(exp);
			sendMessage(player, "You took " + exp + " experience, the ExpVault is now empty.");
		}
		return;
	}

	private void addToSign(PlayerInteractEvent event, Player player) {
		Sign sign = (Sign) event.getClickedBlock().getState();
		String expLine = sign.getLine(2);
		int expOnSign = Integer.parseInt(expLine.replace("Exp: ", ""));

        //Update the name incase of name change
        sign.setLine(1, player.getName());

		int maxExp = config.getInt("experience.max");
		//If sign is already full, return.
		if (!(expOnSign < maxExp)) {
            sign.update();
			sendMessage(player, "That ExpVault is full!");
			return;
		}

		int level = player.getLevel();
		float progress = player.getExp();

		boolean depositAll = true;
		boolean signFull = false;
		//Determine number of levels player wants to deposit.
		int levelsToDeposit = 0;
		if (player.isSneaking()) {
			levelsToDeposit = config.getInt("experience.depositShift");
		} else {
			levelsToDeposit = config.getInt("experience.deposit");
		}
		int xpToDeposit = ExpUtil.getXpFrom(level, progress, levelsToDeposit);

		//If player doesn't have enough levels to deposit, set xpToDeposit to max he has.
		if (level < levelsToDeposit) {
			xpToDeposit = ExpUtil.getTotalExperience(level, progress);
			depositAll = false;
		}

		//When trying to deposit more than sign can hold, set xpToDeposit to completely fill sign.
		if (expOnSign + xpToDeposit > maxExp) {
			xpToDeposit = maxExp - expOnSign;
			depositAll = false;
			signFull = true;
		}

		int playerTotalExp = ExpUtil.getTotalExperience(level, progress);
		removeAllExperience(player);
		player.giveExp(playerTotalExp - xpToDeposit);
		int signExp = expOnSign + xpToDeposit;
		sign.setLine(2, "Exp: " + signExp);
		sign.update();

		if (depositAll) {
			if (levelsToDeposit == 1) {
				sendMessage(player, "You deposited " + levelsToDeposit + " level.");
				return;
			}
			sendMessage(player, "You deposited " + levelsToDeposit + " levels.");
		} else {
			if (signFull) {
				sendMessage(player, "You deposited " + xpToDeposit + " experience, because the ExpVault is already full.");
			} else {
				sendMessage(player, "You deposited " + xpToDeposit + " experience, because you do not have enough experience.");
			}
		}
	}

	@EventHandler
	public void onSignChange(SignChangeEvent event) {
		Player player = event.getPlayer();
		if (!event.getLine(0).equalsIgnoreCase("[expvault]")) {
			transfer.handleSignPlace(event);
			return;
		}

		//First line is expbank.
		if (!player.hasPermission("expvault.create")) {
			player.sendMessage(ChatColor.RED + "You do not have permission for that!");
			event.setCancelled(true);
			return;
		}
		if (config.getBoolean("economy.enable")) {
			if (config.getInt("economy.create") != 0) {
				if (econ.getBalance(player) < config.getInt("economy.create")) {
					sendMessage(player, "You do not have enough money!");
					event.setLine(0, "");
					return;
				}
				econ.withdrawPlayer(player, config.getInt("economy.create"));
				sendMessage(player, "ExpVault made! You have been charged " + config.getInt("economy.create") + ".");
			}
		}
		createSign(event, player);
		sendMessage(player, "ExpVault made!");
	}

	private void createSign(SignChangeEvent event, Player player) {
        //Set the first 3 lines
		event.setLine(0, header);
		event.setLine(1, event.getPlayer().getName());
		event.setLine(2, "Exp: 0");

        //Get the PlayerID
        Integer id = ids.getPlayerID(player);
        if (id == null) {
            //No ID given yet
            id = ids.createID(player);
        }

        //Set the last line
        event.setLine(3, String.valueOf(id));
	}

	private void removeAllExperience(Player player) {
		player.setTotalExperience(0);
		player.setLevel(0);
		player.setExp(0);
	}

	private boolean checkSignIntegrity(Sign sign, Player player) {
		if (sign.getLine(0).equals(header)) {
			if (sign.getLine(0).isEmpty() || sign.getLine(1).isEmpty() || sign.getLine(2).isEmpty() || sign.getLine(3).isEmpty()) {
				debugMessage(sign);
				return false;
			}
		}
		return true;
	}
	
	private void debugMessage(Sign sign){
		logger.log(Level.SEVERE, "ExpVault had wrong formatting!");
		logger.log(Level.INFO, "Line 0: " + sign.getLine(0));
		logger.log(Level.INFO, "Line 1: " + sign.getLine(0));
		logger.log(Level.INFO, "Line 2: " + sign.getLine(0));
		logger.log(Level.INFO, "Line 3: " + sign.getLine(0));
		logger.log(Level.INFO, "Location: " + sign.getWorld() + "," + sign.getX() + "," + sign.getY() + "," + sign.getZ());
	}
}
