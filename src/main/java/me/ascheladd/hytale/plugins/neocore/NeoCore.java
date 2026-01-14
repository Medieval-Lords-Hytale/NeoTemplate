package me.ascheladd.hytale.plugins.neocore;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public final class NeoCore extends JavaPlugin {

    /**
     * Creates a new NeoCore instance.
     *
     * @param init the plugin initialization data provided by the server
     */
    public NeoCore(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.setupCommands();
    }

    @Override
    protected void start() {
        // PermissionsModule.get().addUserToGroup(new UUID(0,0), "ANONYMOUS");
        // CommandManager.get().register(new WebServerCommand(this.loginCodeStore));
        this.setupAnonymousUser();
    }

    void setupAnonymousUser() {
    }

    void setupCommands() {
    }

    @Override
    protected void shutdown() {
        
    }
}
