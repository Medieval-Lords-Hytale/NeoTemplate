# Hytale Server API Guide

## Event System

### Overview

Hytale uses a lambda-based event registration system through `EventRegistry`, not annotation-based handlers like Minecraft/Bukkit. Events are registered programmatically using method references or lambda expressions.

### Key Differences from Minecraft/Bukkit

| Minecraft/Bukkit | Hytale |
|------------------|--------|
| `@EventHandler` annotation | No annotations |
| `implements Listener` | Regular class |
| `PluginManager.registerEvents()` | `plugin.getEventRegistry().register()` |
| Event priority via annotation param | Priority as method parameter |

### Event Registration

#### Basic Synchronous Event

```java
public class MyListener {
    private final MyPlugin plugin;
    
    public MyListener(MyPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void register() {
        // Register with method reference
        plugin.getEventRegistry().register(
            PlayerConnectEvent.class,
            this::onPlayerConnect
        );
        
        // Or with lambda
        plugin.getEventRegistry().register(
            PlayerDisconnectEvent.class,
            event -> {
                // Handle event inline
            }
        );
    }
    
    private void onPlayerConnect(PlayerConnectEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().info("Player connected: " + player.getName());
    }
}
```

#### Event Registration with Priority

```java
// Using EventPriority enum
plugin.getEventRegistry().register(
    EventPriority.HIGH,
    PlayerConnectEvent.class,
    this::onPlayerConnect
);

// Using numeric priority (short)
plugin.getEventRegistry().register(
    (short) 100,  // Higher = earlier execution
    PlayerConnectEvent.class,
    this::onPlayerConnect
);
```

#### Async Events

```java
plugin.getEventRegistry().registerAsync(
    SomeAsyncEvent.class,
    future -> future.thenApply(event -> {
        // Handle async event
        // Must return CompletableFuture<EventType>
        return CompletableFuture.completedFuture(event);
    })
);
```

### Sync vs Async Event Registration

#### Synchronous Events (`.register()`)

**When to use**: Most common events (player join, damage, interactions)

**How it works**:
- Handler runs on the **main game thread**
- **Blocks** execution until handler completes
- Simple `Consumer<Event>` - just accept event, no return value
- **Must be fast** - slow handlers lag the server

```java
// Sync registration - simple Consumer
plugin.getEventRegistry().register(
    PlayerConnectEvent.class,
    event -> {
        // Runs on main thread, blocks until complete
        Player player = event.getPlayer();
        loadPlayerData(player); // Should be quick!
    }
);
```

**Pros:**
- Simple to use
- Direct access to game state
- Guaranteed execution order
- Can modify event/world state safely

**Cons:**
- Blocks main thread
- Slow operations cause server lag
- Can't do I/O operations (database, file, network)

#### Asynchronous Events (`.registerAsync()`)

**When to use**: Events that need heavy I/O or slow operations

**How it works**:
- Handler runs on a **separate thread**
- **Non-blocking** - doesn't stop game execution
- Uses `Function<CompletableFuture<Event>, CompletableFuture<Event>>`
- **Must return** a `CompletableFuture`

```java
// Async registration - Function returning CompletableFuture
plugin.getEventRegistry().registerAsync(
    PlayerConnectEvent.class,
    futureEvent -> futureEvent.thenApplyAsync(event -> {
        // Runs on separate thread, doesn't block server
        Player player = event.getPlayer();
        
        // Can do slow operations safely
        loadFromDatabase(player);
        fetchFromAPI(player);
        writeToFile(player);
        
        // Must return the event
        return event;
    })
);

// More complex example with error handling
plugin.getEventRegistry().registerAsync(
    PlayerConnectEvent.class,
    futureEvent -> futureEvent.thenComposeAsync(event -> {
        // Chain async operations
        return CompletableFuture.supplyAsync(() -> {
            // Do slow work
            loadPlayerData(event.getPlayer());
            return event;
        }).exceptionally(error -> {
            // Handle errors
            getLogger().severe("Failed to load player: " + error);
            return event;
        });
    })
);
```

**Pros:**
- Doesn't block main thread
- Can do I/O operations (database, files, network)
- Perfect for slow operations
- Server stays responsive

**Cons:**
- More complex code (`CompletableFuture` chain)
- **Cannot modify game state** (not on main thread)
- Race conditions possible
- Harder to debug

### Key Differences Summary

| Feature | Sync (`.register()`) | Async (`.registerAsync()`) |
|---------|---------------------|----------------------------|
| **Thread** | Main game thread | Separate thread pool |
| **Blocking** | Yes - blocks server | No - non-blocking |
| **Signature** | `Consumer<Event>` | `Function<CompletableFuture<Event>, CompletableFuture<Event>>` |
| **Return value** | None (void) | Must return `CompletableFuture<Event>` |
| **I/O operations** | ❌ Never! Will lag server | ✅ Safe and recommended |
| **Modify game state** | ✅ Safe | ❌ Not thread-safe |
| **Execution order** | Guaranteed by priority | May overlap |
| **Error handling** | Try-catch | `.exceptionally()` |
| **Use for** | Quick reactions, game logic | Database, files, network, heavy computation |

### When to Choose Which

**Use Sync (`.register()`) for:**
- ✅ Quick operations (< 1ms)
- ✅ Game state modifications
- ✅ Event cancellation
- ✅ Guaranteed execution order
- ✅ Simple event reactions
- **Examples**: damage modification, chat filters, permission checks

**Use Async (`.registerAsync()`) for:**
- ✅ Database queries
- ✅ File I/O
- ✅ Network requests
- ✅ Heavy computations
- ✅ Anything that takes > 1ms
- **Examples**: player data loading, statistics tracking, web API calls

### Common Pitfall: Sync Handler Doing I/O

```java
// ❌ BAD - Database call in sync handler!
plugin.getEventRegistry().register(
    PlayerConnectEvent.class,
    event -> {
        // This blocks the main thread = server lag!
        database.loadPlayer(event.getPlayer()); // Takes 50ms = BAD!
    }
);

// ✅ GOOD - Database call in async handler
plugin.getEventRegistry().registerAsync(
    PlayerConnectEvent.class,
    futureEvent -> futureEvent.thenApplyAsync(event -> {
        // This doesn't block the server
        database.loadPlayer(event.getPlayer()); // Takes 50ms = OK!
        return event;
    })
);
```

### Bridging Async to Sync

If you need to modify game state after async work:

```java
plugin.getEventRegistry().registerAsync(
    PlayerConnectEvent.class,
    futureEvent -> futureEvent.thenApplyAsync(event -> {
        // Do async work
        PlayerData data = database.loadPlayer(event.getPlayer());
        
        // Schedule sync task to modify game state
        plugin.getTaskRegistry().execute(() -> {
            // Now on main thread - safe to modify game
            event.getPlayer().sendMessage("Welcome back!");
            applyPlayerData(event.getPlayer(), data);
        });
        
        return event;
    })
);
```

#### Global Events

Global events fire for all instances/worlds:

```java
plugin.getEventRegistry().registerGlobal(
    SomeGlobalEvent.class,
    this::handleGlobalEvent
);
```

#### Unhandled Events

For events that haven't been handled by other listeners:

```java
plugin.getEventRegistry().registerUnhandled(
    SomeEvent.class,
    this::handleIfNotHandled
);
```

### Available Event Types

#### Player Events

Located in: `com.hypixel.hytale.server.core.event.events.player`

| Event Class | Description | Key Methods |
|-------------|-------------|-------------|
| `PlayerConnectEvent` | Player joins server | `getPlayer()`, `getPlayerRef()`, `getWorld()` |
| `PlayerDisconnectEvent` | Player leaves server | `getPlayerRef()`, `getDisconnectReason()` |
| `PlayerInteractEvent` | Player interacts with world | TBD |
| `PlayerCraftEvent` | Player crafts item | TBD |
| `PlayerMouseMotionEvent` | Player moves mouse | TBD |
| `PlayerRefEvent` | Base player event | `getPlayerRef()` |
| `PlayerSetupDisconnectEvent` | Player disconnects during setup | TBD |

#### Entity Events

Located in: `com.hypixel.hytale.server.core.event.events.entity`

- `EntityEvent` - Base entity event
- `EntityRemoveEvent` - Entity is removed
- `LivingEntityInventoryChangeEvent` - Living entity inventory changes
- `LivingEntityUseBlockEvent` - Living entity uses block

#### Damage/Combat Events

Located in: `com.hypixel.hytale.server.core.modules.entity.damage.event`

- `KillFeedEvent` - Kill feed message events
  - `KillFeedEvent.Display`
  - `KillFeedEvent.KillerMessage`
  - `KillFeedEvent.DecedentMessage`

### Event Interface Hierarchy

```
IBaseEvent<KeyType>
├── IEvent<KeyType>
├── IAsyncEvent<KeyType>
├── IProcessedEvent
└── ICancellable
```

### EventRegistry Methods

#### Synchronous Registration

```java
// Simple registration (no key)
<EventType extends IBaseEvent<Void>> EventRegistration<Void, EventType> 
    register(Class<? super EventType>, Consumer<EventType>)

// With priority
<EventType extends IBaseEvent<Void>> EventRegistration<Void, EventType> 
    register(EventPriority, Class<? super EventType>, Consumer<EventType>)

// With numeric priority
<EventType extends IBaseEvent<Void>> EventRegistration<Void, EventType> 
    register(short priority, Class<? super EventType>, Consumer<EventType>)

// With key type
<KeyType, EventType extends IBaseEvent<KeyType>> EventRegistration<KeyType, EventType> 
    register(Class<? super EventType>, KeyType, Consumer<EventType>)
```

#### Async Registration

```java
<EventType extends IAsyncEvent<Void>> EventRegistration<Void, EventType> 
    registerAsync(
        Class<? super EventType>, 
        Function<CompletableFuture<EventType>, CompletableFuture<EventType>>
    )
```

#### Global Registration

```java
<KeyType, EventType extends IBaseEvent<KeyType>> EventRegistration<KeyType, EventType> 
    registerGlobal(Class<? super EventType>, Consumer<EventType>)
```

#### Unhandled Registration

```java
<KeyType, EventType extends IBaseEvent<KeyType>> EventRegistration<KeyType, EventType> 
    registerUnhandled(Class<? super EventType>, Consumer<EventType>)
```

### Complete Example

```java
package com.example.myplugin.listeners;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.event.EventPriority;

import com.example.myplugin.MyPlugin;

public class PlayerListener {
    private final MyPlugin plugin;
    
    public PlayerListener(MyPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Register all event handlers.
     * Call this in your plugin's setup() method.
     */
    public void register() {
        // Player connect with high priority
        plugin.getEventRegistry().register(
            EventPriority.HIGH,
            PlayerConnectEvent.class,
            this::onPlayerConnect
        );
        
        // Player disconnect with normal priority
        plugin.getEventRegistry().register(
            PlayerDisconnectEvent.class,
            this::onPlayerDisconnect
        );
    }
    
    private void onPlayerConnect(PlayerConnectEvent event) {
        Player player = event.getPlayer();
        
        plugin.getLogger().info("Player " + player.getName() + " connected!");
        
        // Load player data, send welcome message, etc.
    }
    
    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        String playerName = event.getPlayerRef().toString();
        String reason = event.getDisconnectReason().toString();
        
        plugin.getLogger().info("Player " + playerName + " disconnected: " + reason);
        
        // Save player data, notify others, etc.
    }
}
```

### Plugin Integration

In your main plugin class:

```java
package com.example.myplugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.example.myplugin.listeners.PlayerListener;

public class MyPlugin extends JavaPlugin {
    private PlayerListener playerListener;
    
    public MyPlugin(JavaPluginInit init) {
        super(init);
    }
    
    @Override
    protected void setup() {
        // Initialize and register listeners
        playerListener = new PlayerListener(this);
        playerListener.register();
        
        getLogger().info("MyPlugin enabled!");
    }
    
    @Override
    protected void shutdown() {
        // Event registrations are automatically cleaned up
        getLogger().info("MyPlugin disabled!");
    }
}
```

## Plugin Base Class

### Available Registries

Access these via getter methods in your `JavaPlugin` or `PluginBase`:

```java
// Event system - Subscribe to game events
EventRegistry getEventRegistry()

// Commands - Register custom commands
CommandRegistry getCommandRegistry()

// Tasks/Scheduling - Schedule delayed/repeating tasks
TaskRegistry getTaskRegistry()

// Entities - Register custom entity types
EntityRegistry getEntityRegistry()

// Blocks - Register custom block states
BlockStateRegistry getBlockStateRegistry()

// Client features - Register client-side features
ClientFeatureRegistry getClientFeatureRegistry()

// Assets - Register game assets
AssetRegistry getAssetRegistry()

// Component registries - Register ECS components
ComponentRegistryProxy<EntityStore> getEntityStoreRegistry()
ComponentRegistryProxy<ChunkStore> getChunkStoreRegistry()
```

### Registry Purposes

#### EventRegistry
**Purpose**: Subscribe to and handle game events (player join, entity damage, etc.)

**Use for**: 
- Responding to player actions
- Listening to entity events
- Monitoring world changes
- NOT for modifying game mechanics directly

#### EntityRegistry
**Purpose**: Register custom entity types and behaviors

**Use for**:
- Creating new entity types (custom mobs, NPCs, etc.)
- Defining entity spawn logic
- Registering entity codecs for serialization

```java
@Override
protected void setup() {
    // Register a custom entity type
    getEntityRegistry().registerEntity(
        "my_custom_mob",           // Entity ID
        MyCustomMob.class,          // Entity class
        world -> new MyCustomMob(world),  // Factory function
        myCustomMobCodec            // Codec for saving/loading
    );
}
```

#### BlockStateRegistry  
**Purpose**: Register custom block types and states

**Use for**:
- Creating custom blocks
- Defining block behaviors
- Registering block variants/states

#### TaskRegistry
**Purpose**: Schedule tasks to run later or repeatedly

**Use for**:
- Delayed actions
- Repeating tasks (autosave, etc.)
- Async operations

#### ComponentRegistryProxy (EntityStore/ChunkStore)
**Purpose**: Register Entity Component System (ECS) components

**Use for**:
- Adding data components to entities
- Storing custom data on chunks
- Integrating with Hytale's ECS architecture

### Plugin Lifecycle Methods

```java
protected void setup()      // Called during plugin setup phase
protected void start()      // Called when plugin starts
protected void shutdown()   // Called when plugin shuts down
```

### Plugin State

```java
boolean isEnabled()   // Check if plugin is enabled
boolean isDisabled()  // Check if plugin is disabled
PluginState getState() // Get current state
```

## Best Practices

1. **Create separate listener classes** - Don't register all events in your main plugin class
2. **Use method references** - More readable than lambdas for simple handlers
3. **Call register() in setup()** - Register events during plugin setup phase
4. **Don't store event instances** - They're not meant to be retained
5. **Use appropriate priorities** - Default is usually fine unless order matters
6. **Clean async operations** - Ensure async event handlers complete properly
7. **Log errors** - Always log exceptions in event handlers
8. **Test event order** - When using priorities, verify execution order

## Troubleshooting

### Events Not Firing

- Ensure `listener.register()` is called in `setup()`
- Check event class is correct (use exact class, not parent)
- Verify plugin is enabled
- Check logs for registration errors

### Multiple Handlers Conflicting

- Use event priorities to control execution order
- Consider using `registerUnhandled()` for fallback handlers
- Check if event is cancellable and being cancelled early

### Async Issues

- Use `registerAsync()` for async events, not regular `register()`
- Ensure async handlers return `CompletableFuture<EventType>`
- Don't block the event thread

## Modifying Game Mechanics (Damage, etc.)

### Damage System

Hytale uses an **Entity Component System (ECS)** for damage, not simple events. To modify damage:

#### Option 1: Entity Event Systems (Recommended)

Create a custom `DamageEventSystem` to intercept and modify damage:

```java
public class CustomDamageSystem extends DamageEventSystem {
    
    @Override
    public void handle(
        int entityIndex,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> buffer,
        Damage damage
    ) {
        // Modify damage amount
        float originalDamage = damage.getAmount();
        float modifiedDamage = originalDamage * 1.5f; // 50% more damage
        damage.setAmount(modifiedDamage);
        
        // Check damage source
        Damage.Source source = damage.getSource();
        
        // Check damage cause
        DamageCause cause = damage.getCause();
        
        // Cancel damage
        if (shouldCancelDamage(damage)) {
            damage.cancel();
        }
        
        // Access metadata
        damage.getMeta(Damage.HIT_LOCATION);  // Where hit occurred
        damage.getMeta(Damage.HIT_ANGLE);     // Angle of hit
        damage.getMeta(Damage.BLOCKED);       // Was it blocked?
    }
    
    @Override
    public Query<EntityStore> getQuery() {
        // Define which entities this system applies to
        return Query.all(HealthComponent.class); // Example
    }
}
```

Register the system:

```java
@Override
protected void setup() {
    // Register your damage system with EntityStore registry
    getEntityStoreRegistry().register(
        new CustomDamageSystem(),
        () -> isEnabled(),  // Condition
        () -> {}            // Cleanup
    );
}
```

#### Option 2: Events (Limited)

For simpler damage monitoring (not modification):

```java
// There may be damage-related events, but they're for observation
// Look for: DamageBlockEvent, KillFeedEvent
plugin.getEventRegistry().register(
    KillFeedEvent.class,
    event -> {
        // React to kills, but can't modify damage here
    }
);
```

#### Damage Class API

```java
// Damage properties
float getAmount()                    // Current damage amount
void setAmount(float)                // Modify damage
float getInitialAmount()             // Original damage (before modifications)
DamageCause getCause()               // Why damage happened
Damage.Source getSource()            // Who/what caused it
void cancel()                        // Cancel the damage

// Metadata (via MetaKeys)
Damage.HIT_LOCATION                  // Vector4d - where hit occurred
Damage.HIT_ANGLE                     // Float - angle of attack
Damage.BLOCKED                       // Boolean - was damage blocked
Damage.KNOCKBACK_COMPONENT           // Knockback data
Damage.CAMERA_EFFECT                 // Camera shake effect
Damage.IMPACT_PARTICLES              // Particle effects
Damage.IMPACT_SOUND_EFFECT           // Sound on impact
```

### ECS Systems vs Events

| Feature | Events (EventRegistry) | ECS Systems (ComponentRegistry) |
|---------|----------------------|--------------------------------|
| **Purpose** | React to things happening | Modify game logic/data |
| **Timing** | After the fact | During execution |
| **Modification** | Limited/none | Full control |
| **Complexity** | Simple | More complex |
| **Use for** | Logging, notifications, side effects | Game mechanics, damage, movement |

**Rule of thumb**: 
- **Listen/React** → Use EventRegistry
- **Modify/Control** → Use ECS Systems (ComponentRegistry)

### Example: Custom Damage Modifier

```java
package com.example.plugin.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public class ArmorDamageReductionSystem extends DamageEventSystem {
    
    @Override
    public void handle(
        int entityIndex,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> buffer,
        Damage damage
    ) {
        // Get entity components
        ComponentArray<ArmorComponent> armorArray = chunk.getComponentArray(ArmorComponent.class);
        if (armorArray == null) return;
        
        ArmorComponent armor = armorArray.get(entityIndex);
        if (armor == null) return;
        
        // Calculate reduction
        float armorValue = armor.getArmorValue();
        float reduction = 1.0f - (armorValue / 100.0f);
        
        // Apply reduction
        float newDamage = damage.getAmount() * reduction;
        damage.setAmount(newDamage);
        
        getLogger().info("Reduced damage from " + damage.getInitialAmount() + 
                        " to " + newDamage + " (armor: " + armorValue + ")");
    }
    
    @Override
    public Query<EntityStore> getQuery() {
        // Only apply to entities with armor
        return Query.all(ArmorComponent.class, HealthComponent.class);
    }
}
```

## Additional Resources

- Hytale Server JAR: `~/.m2/repository/com/hypixel/hytale/HytaleServer-parent/`
- Decompile with: `javap -cp <jar> -p com.hypixel.hytale.server.core.event.EventRegistry`
- Examine classes: `jar -tf <jar> | grep -i "damage"`
- Example plugins: Check your workspace for reference implementations

### Useful Classes to Explore

```bash
# Event system
com.hypixel.hytale.event.EventRegistry

# Damage system
com.hypixel.hytale.server.core.modules.entity.damage.Damage
com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem
com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems

# ECS architecture
com.hypixel.hytale.component.system.EntityEventSystem
com.hypixel.hytale.component.ComponentRegistry

# Entity/Block registries
com.hypixel.hytale.server.core.modules.entity.EntityRegistry
com.hypixel.hytale.server.core.universe.world.meta.BlockStateRegistry
```
