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

# UI System
com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage
com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager
com.hypixel.hytale.server.core.entity.entities.player.windows.Window
com.hypixel.hytale.server.core.ui.builder.UICommandBuilder
```

## Creating User Interfaces

Hytale provides three main UI systems for different purposes:

### 1. Custom Pages (Recommended for Custom UI)

**What**: Full-screen custom UI pages that can display any content  
**Use for**: Menus, dialogs, shops, quest interfaces, custom GUIs

#### Creating a Custom Page

```java
package com.example.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;

public class MyCustomPage extends CustomUIPage {
    
    public MyCustomPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.PERSISTENT);
        // Lifetime options:
        // PERSISTENT - Stays open until explicitly closed
        // TEMPORARY - Closes on certain actions
    }
    
    /**
     * Build the UI structure.
     * This is where you define your UI elements.
     */
    @Override
    public void build(
        Ref<EntityStore> playerEntity,
        UICommandBuilder ui,
        UIEventBuilder events,
        Store<EntityStore> store
    ) {
        // Clear existing UI
        ui.clear("root");
        
        // Add a container
        ui.append("root", "main-container");
        
        // Add text
        ui.append("main-container", "title-text");
        ui.set("title-text.text", "Welcome to My Plugin!");
        
        // Add a button
        ui.append("main-container", "close-button");
        ui.set("close-button.text", "Close");
        ui.set("close-button.type", "button");
        
        // Register button click event
        events.registerEvent("close-button", "click", "close-clicked");
    }
    
    /**
     * Handle UI events (button clicks, etc.)
     */
    @Override
    public void handleDataEvent(
        Ref<EntityStore> playerEntity,
        Store<EntityStore> store,
        String eventId
    ) {
        switch (eventId) {
            case "close-clicked":
                close(); // Close the page
                break;
            // Handle other events...
        }
    }
    
    /**
     * Called when the page is dismissed/closed
     */
    @Override
    public void onDismiss(Ref<EntityStore> playerEntity, Store<EntityStore> store) {
        // Cleanup when page closes
    }
}
```

#### Opening a Custom Page

```java
// In your event handler or command
Player player = event.getPlayer();

// Get the player's page manager
PageManager pageManager = player.getPageManager();

// Create and open the page
MyCustomPage page = new MyCustomPage(player.getPlayerRef());
pageManager.openCustomPage(
    player.getEntityRef(),
    player.getWorld().getStore(),
    page
);
```

#### UICommandBuilder API

```java
// Element management
ui.clear("elementId")                    // Clear element's children
ui.remove("elementId")                   // Remove element
ui.append("parentId", "childId")         // Add child to parent
ui.insertBefore("existingId", "newId")   // Insert before element

// Setting properties
ui.set("elementId.text", "Hello")        // Set text
ui.set("elementId.visible", true)        // Set visibility
ui.set("elementId.enabled", false)       // Enable/disable
ui.set("elementId.width", 200)           // Set width (pixels)
ui.set("elementId.height", 100)          // Set height
ui.set("elementId.x", 50)                // Set X position
ui.set("elementId.y", 50)                // Set Y position
ui.set("elementId.color", "#FF0000")     // Set color (hex)
ui.set("elementId.image", "texture:ui/button") // Set image/texture

// Arrays and lists
ui.set("elementId.items", new String[] {"Item1", "Item2"})
ui.set("elementId.data", Arrays.asList("A", "B", "C"))
```

### 2. Windows (Inventory-Style UI)

**What**: Container-based UIs similar to inventory screens  
**Use for**: Chests, furnaces, crafting tables, custom inventories

```java
package com.example.plugin.ui;

import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.google.gson.JsonObject;

public class MyCustomWindow extends Window {
    
    public MyCustomWindow() {
        super(WindowType.CUSTOM); // Or other WindowType
    }
    
    @Override
    public JsonObject getData() {
        JsonObject data = new JsonObject();
        // Define window data (title, slots, etc.)
        data.addProperty("title", "My Custom Window");
        return data;
    }
    
    @Override
    protected boolean onOpen0() {
        // Called when window opens
        return true; // Return false to cancel opening
    }
    
    @Override
    protected void onClose0() {
        // Called when window closes
    }
    
    @Override
    public void handleAction(
        Ref<EntityStore> playerEntity,
        Store<EntityStore> store,
        WindowAction action
    ) {
        // Handle player actions (clicks, etc.)
    }
}
```

#### Opening a Window

```java
Player player = event.getPlayer();
WindowManager windowManager = player.getWindowManager();

MyCustomWindow window = new MyCustomWindow();
windowManager.open(window);
```

### 3. Entity UI Components (Floating UI)

**What**: UI elements attached to entities (health bars, nameplates, etc.)  
**Use for**: Floating text, health displays, damage numbers, entity labels

```java
// Entity UI components are typically defined in assets
// and attached to entities via components
// Less common for plugin use - primarily for visual feedback

// Example: Combat text (damage numbers)
// These are usually triggered by game events and use predefined animations
```

### UI System Comparison

| Feature | Custom Pages | Windows | Entity UI |
|---------|-------------|---------|-----------|
| **Layout** | Full custom layout | Container/grid based | Floating/3D space |
| **Complexity** | High flexibility | Medium (inventory-style) | Low (visual only) |
| **Use case** | Menus, shops, dialogs | Inventories, containers | Health bars, damage |
| **Interaction** | Buttons, inputs, events | Click/drag items | Limited |
| **Best for** | Custom game UIs | Storage interfaces | Visual feedback |

### Complete Example: Shop UI

```java
package com.example.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;

public class ShopPage extends CustomUIPage {
    private int selectedItem = -1;
    
    public ShopPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.PERSISTENT);
    }
    
    @Override
    public void build(
        Ref<EntityStore> playerEntity,
        UICommandBuilder ui,
        UIEventBuilder events,
        Store<EntityStore> store
    ) {
        // Clear and build UI
        ui.clear("root");
        ui.append("root", "shop-container");
        
        // Title
        ui.append("shop-container", "title");
        ui.set("title.text", "Village Shop");
        ui.set("title.fontSize", 24);
        
        // Item list
        ui.append("shop-container", "items-list");
        String[] items = {"Sword - 100g", "Shield - 75g", "Potion - 25g"};
        ui.set("items-list.items", items);
        
        // Buy button
        ui.append("shop-container", "buy-button");
        ui.set("buy-button.text", "Buy Item");
        ui.set("buy-button.enabled", false); // Disabled until item selected
        
        // Close button
        ui.append("shop-container", "close-button");
        ui.set("close-button.text", "Close Shop");
        
        // Register events
        events.registerEvent("items-list", "select", "item-selected");
        events.registerEvent("buy-button", "click", "buy-clicked");
        events.registerEvent("close-button", "click", "close-clicked");
    }
    
    @Override
    public void handleDataEvent(
        Ref<EntityStore> playerEntity,
        Store<EntityStore> store,
        String eventId
    ) {
        switch (eventId) {
            case "item-selected":
                // Item was selected in list
                selectedItem = 0; // Get actual index from event
                
                // Update UI to enable buy button
                UICommandBuilder ui = new UICommandBuilder();
                ui.set("buy-button.enabled", true);
                sendUpdate(ui);
                break;
                
            case "buy-clicked":
                if (selectedItem >= 0) {
                    // Process purchase
                    processPurchase(playerEntity, selectedItem);
                }
                break;
                
            case "close-clicked":
                close();
                break;
        }
    }
    
    private void processPurchase(Ref<EntityStore> playerEntity, int itemIndex) {
        // Handle purchase logic
        // Deduct currency, give item, etc.
    }
}
```

### Opening the Shop

```java
// In your command or NPC interaction handler
@Override
public void execute(Player player, String[] args) {
    ShopPage shop = new ShopPage(player.getPlayerRef());
    player.getPageManager().openCustomPage(
        player.getEntityRef(),
        player.getWorld().getStore(),
        shop
    );
}
```

### Best Practices

1. **Keep UI updates small** - Only update what changed, don't rebuild entire UI
2. **Use events properly** - Register all interactive elements with UIEventBuilder
3. **Handle dismissal** - Always implement `onDismiss()` for cleanup
4. **Validate input** - Check player actions in `handleDataEvent()`
5. **Use appropriate lifetime** - PERSISTENT for menus, TEMPORARY for notifications
6. **Cache UI state** - Store state in your page class, not in UI repeatedly
7. **Test thoroughly** - UI bugs are hard to debug, test all interactions

### UI Elements (Typical)

While exact elements depend on Hytale's UI framework:
- **Containers**: Layout panels, grids
- **Text**: Labels, titles, descriptions
- **Buttons**: Clickable elements
- **Lists**: Scrollable item lists
- **Images**: Icons, textures
- **Input**: Text fields (if available)
- **Sliders**: Value selection (if available)

## Rendering Text in the World

For text that appears in the 3D game world (not on UI pages):

### 1. Nameplates (Entity Labels)

**What**: Text that floats above entities (like player names)  
**Use for**: NPC names, entity labels, status indicators

#### Adding a Nameplate to an Entity

```java
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

// Add nameplate component to an entity
Ref<EntityStore> entityRef = ...; // Your entity reference
ComponentAccessor<EntityStore> accessor = store.getComponentAccessor();

// Create and add nameplate
Nameplate nameplate = new Nameplate("Custom NPC Name");
accessor.addComponent(entityRef, nameplate);

// Update nameplate text later
Nameplate existing = accessor.getComponent(entityRef, Nameplate.class);
if (existing != null) {
    existing.setText("Updated Name");
    // Nameplate will sync to clients automatically
}
```

#### Nameplate Features

```java
// Create nameplate
Nameplate nameplate = new Nameplate();
nameplate.setText("Shop Keeper");  // Set text
String text = nameplate.getText(); // Get text

// Nameplate automatically:
// - Floats above entity
// - Faces the player
// - Updates when text changes (consumeNetworkOutdated())
// - Syncs to all nearby clients
```

### 2. Combat Text (Floating Damage Numbers)

**What**: Animated text that appears during combat (damage numbers, etc.)  
**Use for**: Damage indicators, healing numbers, status messages

#### Combat Text Component

```java
import com.hypixel.hytale.server.core.modules.entityui.asset.CombatTextUIComponent;

// Combat text is typically defined in assets and triggered by events
// Used for damage numbers, healing, etc.
// Supports animations (scale, position, opacity)

// Example: Display damage number above entity
// (Exact API may vary - combat text is often asset-driven)
```

#### Combat Text Features

- Animated (position, scale, opacity)
- Automatically rises and fades
- Multiple texts can stack
- Color customizable
- Used for damage/healing indicators

### 3. Entity UI Components (Advanced)

**What**: Custom UI elements attached to entities in 3D space  
**Use for**: Health bars, custom indicators, interactive prompts

#### Adding UI Components to Entities

```java
import com.hypixel.hytale.server.core.modules.entityui.UIComponentList;

// Add UI component list to entity
UIComponentList uiList = new UIComponentList();
uiList.components = new String[] {
    "my_custom_ui_component"  // Asset ID of your UI component
};
uiList.update(); // Updates internal IDs

accessor.addComponent(entityRef, uiList);
```

#### Creating Custom Entity UI (Asset-Based)

Entity UI components are typically defined in JSON assets:

```json
{
  "type": "combat_text",
  "id": "my_damage_indicator",
  "hitbox_offset": [0, 2.0],
  "animations": {
    "appear": { "duration": 0.5, "type": "scale" },
    "fade": { "duration": 1.0, "type": "opacity" }
  }
}
```

Then reference in code:
```java
EntityUIComponent.getAssetStore().get("my_damage_indicator");
```

### Comparison: World Text Options

| Method | Type | Movement | Interactivity | Best For |
|--------|------|----------|---------------|----------|
| **Nameplate** | Simple text | Follows entity | None | Entity names, labels |
| **Combat Text** | Animated text | Rises/fades | None | Damage, healing, feedback |
| **Entity UI** | Complex UI | Attached to entity | Limited | Health bars, indicators |
| **Custom Pages** | Full UI | Screen overlay | Full | Menus, dialogs |

### Practical Examples

#### Example 1: NPC with Custom Name

```java
public class CustomNPC {
    
    public void spawnNPC(World world, Vector3d position) {
        // Create NPC entity
        Ref<EntityStore> npc = world.spawnEntity("npc", position);
        ComponentAccessor<EntityStore> accessor = world.getStore().getComponentAccessor();
        
        // Add nameplate
        Nameplate nameplate = new Nameplate("§6[Quest] Village Elder");
        accessor.addComponent(npc, nameplate);
        
        // NPC now has golden "[Quest] Village Elder" floating above
    }
    
    public void updateNPCStatus(Ref<EntityStore> npc, ComponentAccessor<EntityStore> accessor, String status) {
        Nameplate nameplate = accessor.getComponent(npc, Nameplate.class);
        if (nameplate != null) {
            nameplate.setText("§6[Quest] Village Elder\n§7" + status);
            // Multi-line nameplate showing NPC name and status
        }
    }
}
```

#### Example 2: Damage Display System

```java
public class DamageDisplaySystem extends DamageEventSystem {
    
    @Override
    public void handle(
        int entityIndex,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> buffer,
        Damage damage
    ) {
        float amount = damage.getAmount();
        
        // Display damage as floating text
        // This would typically use CombatTextUIComponent
        // which shows animated damage numbers above the entity
        
        // The combat text system automatically:
        // - Spawns text at hit location
        // - Animates upward and fades
        // - Color codes based on damage type
        
        // Exact implementation depends on Hytale's combat text API
    }
}
```

#### Example 3: Shop NPC with Interactive Nameplate

```java
public void createShopKeeper(World world, Vector3d position) {
    // Spawn NPC
    Ref<EntityStore> shopkeeper = world.spawnEntity("npc", position);
    ComponentAccessor<EntityStore> accessor = world.getStore().getComponentAccessor();
    
    // Add nameplate with instructions
    Nameplate nameplate = new Nameplate(
        "§e§lShop Keeper\n" +
        "§7Right-click to browse"
    );
    accessor.addComponent(shopkeeper, nameplate);
    
    // When player right-clicks NPC, open shop UI page
    // (via interaction handler)
}
```

### Notes on World Text

1. **Nameplates are simplest** - Just text, follows entity, auto-faces player
2. **Combat text is asset-driven** - Defined in JSON, triggered by events
3. **Entity UI is advanced** - Full UI components in 3D space, complex setup
4. **Color codes work** - Use `§` formatting (e.g., `§6` for gold, `§l` for bold)
5. **Multi-line supported** - Use `\n` for line breaks in nameplates
6. **Auto-syncing** - Changes sync to clients automatically
7. **Performance** - Many nameplates can impact performance; use wisely

### Common Use Cases

**Use Nameplates for:**
- NPC names and titles
- Entity type labels
- Status indicators ("Friendly", "Hostile")
- Quest markers
- Shop/service indicators

**Use Combat Text for:**
- Damage numbers
- Healing numbers
- XP gain notifications
- Critical hit indicators
- Status effect triggers

**Use Entity UI for:**
- Health/mana bars
- Boss health displays
- Casting bars
- Buff/debuff indicators
- Complex interactive elements

**Use Custom Pages for:**
- Full menus
- Shops and trading
- Quest dialogs
- Inventory screens
- Settings/configuration
