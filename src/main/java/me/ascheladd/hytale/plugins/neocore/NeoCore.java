package me.ascheladd.hytale.plugins.neocore;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import me.ascheladd.hytale.plugins.neocore.io.IOManager;
import me.ascheladd.hytale.plugins.neocore.io.components.PlayerStatsComponent;
import me.ascheladd.hytale.plugins.neocore.listeners.PlayerDataListener;
import me.ascheladd.hytale.plugins.neocore.systems.CustomDamageSystem;

public final class NeoCore extends JavaPlugin {
    private IOManager ioManager;
    
    public NeoCore(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Initialize IOManager
        ioManager = new IOManager(getLogger());
        
        // Register database(s)
        // TODO: Load these from config file
        String jdbcUrl = "jdbc:mysql://localhost:3306/neocore";
        String username = "your_username";
        String password = "your_password";
        ioManager.registerDatabase("main", jdbcUrl, username, password);
        
        // Register your PlayerIOComponent implementations here
        ioManager.register(new PlayerStatsComponent(this), "main");
        
        // Register event listeners
        PlayerDataListener playerDataListener = new PlayerDataListener(this);
        playerDataListener.register();
        
        // Register ECS systems for modifying game mechanics
        registerDamageSystems();
        
        // CommandManager.get().register(new WebServerCommand(this.loginCodeStore));
    }

    @Override
    protected void start() {
        // PermissionsModule.get().addUserToGroup(new UUID(0,0), "ANONYMOUS");
    }

    @Override
    protected void shutdown() {
        // Shutdown IOManager (closes all database connections)
        if (ioManager != null) {
            ioManager.shutdown();
        }
    }
    
    /**
     * Gets the IOManager instance.
     * 
     * @return the IOManager
     */
    public IOManager getIOManager() {
        return ioManager;
    }
    
    /**
     * Registers custom damage modification systems.
     * These systems intercept and modify damage before it's applied.
     */
    private void registerDamageSystems() {
        // Register custom damage system
        getEntityStoreRegistry().register(
            new CustomDamageSystem(),
            () -> isEnabled(),  // Only active when plugin is enabled
            () -> {             // Cleanup on unregister
                getLogger().info("Unregistered CustomDamageSystem");
            }
        );
        
        getLogger().info("Registered damage modification systems");
        
        // You can register multiple systems:
        // getEntityStoreRegistry().register(new ArmorDamageSystem(), () -> isEnabled(), () -> {});
    }
}
    }
}
