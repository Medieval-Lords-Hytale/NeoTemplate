package me.ascheladd.hytale.neotemplate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public final class NeoTemplate extends JavaPlugin {
    
    public NeoTemplate(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // CommandManager.get().register(new WebServerCommand(this.loginCodeStore));
    }

    @Override
    protected void start() {
        // PermissionsModule.get().addUserToGroup(new UUID(0,0), "ANONYMOUS");
    }

    @Override
    protected void shutdown() {

    }
}