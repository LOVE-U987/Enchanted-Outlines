<div align="center">

# ✨ Enchanted Outlines · 轮廓即宣言

**Every enchanted item deserves its own soul color.**

> *Inspired by the [Enchantment Outlines](https://modrinth.com/resourcepack/glowing-glints) resource pack, but pushed to its absolute limit as a native mod.*

</div>

---

## 🎨 One Glance, One Enchantment — Color Is the Language

Tired of the same old purple enchantment glint? **Enchanted Outlines** injects a complete **visual semantic system** into Minecraft's enchantment framework — every enchantment type is assigned a unique color identity, letting you read the power in your hands in an instant.

> 💡 **Unconfigured enchantments** automatically fall back to the default color. Supports per-enchantment customization via config file or in-game UI.

---

## 🚀 Eight Core Features

### 1. 🧬 Procedural Outline Generation — Zero Textures, Full Compatibility

Traditional resource packs require pre-baked outline textures for every item. **Enchanted Outlines** uses a **custom core shader** to extract the item model's alpha mask in real-time on the GPU, then offsets and renders it in **8 directions**.

```
Original Item Texture → Alpha Mask      
       ↓                                 
  8-Directional Offset Render            
  (N / S / E / W / NE / NW / SE / SW)    
       ↓                                 
  Solid Fill → Blend Overlay → Outline
```

**This means:**

- ✅ **Any modded item works automatically** — no pre-drawn textures needed
- ✅ **Zero bake time** — install and play
- ✅ **Zero pixel readback** — pure GPU computation
- ✅ **Zero cached memory** — generated fresh every frame

---

### 2. 🌐 Full Rendering Context Coverage

From inventory to battlefield, from ground to sky — the outline is everywhere:

| Scene | Rendering Method | Special Handling |
|-------|------------------|------------------|
| 🎒 **Inventory / Hotbar / Container GUI** | 2D Texture Outline | Supports 3D item preview |
| 🤚 **First-person Held** | Vertex Normal Extrusion | Uniform thickness, surface-hugging |
| 👤 **Third-person Held / Dropped Items** | Vertex Normal Extrusion | Dynamic lighting response |
| 🖼️ **Item Frames** | Standard Model Outline | Orientation-adaptive |
| 🛡️ **Worn Armor** | Model Extrusion | Independent `armorThickness` config |
| 🦋 **Worn Elytra** | Model Extrusion | Flight pose synchronization |
| 🔱 **Thrown Trident** | Approximate Box Model | Throwing pose tracking |

---

### 3. 🎯 BEWLR Smart Approximation

Shields, tridents, and spyglasses use procedural rendering (BEWLR) and lack traditional models for outlining. We've built **geometric approximate box models** for them, tracking `blocking` (shield raise) and `throwing` (trident throw) display transforms in real-time to ensure the outline never misaligns.

> ⚠️ Fishing rods, maps, and other placeholder BEWLR models lack geometric shapes and are not outlined — this is a technical limitation, not an oversight.

---

### 4. 🔄 Animation & Variant Native Correctness

Clock hand rotation, compass needle deflection, NBT-driven variant models, damage-induced model switching — all dynamic changes are **resolved from the same model every frame**, so the outline shape stays perfectly synchronized with the item itself.

---

### 5. 🔧 Native Config UI — Dark Amber-Gold Theme

No need to manually edit TOML files. Adjust everything in-game through a polished config interface:

- 🎨 **Dark + Amber-Gold theme**, harmonized with vanilla style
- 📂 **Categorized navigation**: Enchant Colors / Item Colors / Global Settings / Disabled List
- 📜 **Scrollable lists**: Supports large numbers of enchantment/item entries
- 💾 **Instant save**: Changes take effect immediately, no restart required
- 🚫 **Blur background disabled**: Avoids conflicts with certain UI mods

---

### 6. 🔌 Developer-Friendly API

Other mods can programmatically register rules via the event bus, with **priority over config files**:

```java
NeoForge.EVENT_BUS.addListener(OutlineColorEvent.class, event -> {
    // Register enchantment color
    OutlineColorRegistry.registerEnchantmentColor(
        ResourceLocation.fromNamespaceAndPath("mymod", "my_enchant"), 
        0xFFFFA500  // Amber Gold
    );

    // Register item override color
    OutlineColorRegistry.registerItemColor(
        ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 
        0xFF00FF00  // Magic Green
    );

    // Register per-item thickness (overrides global armorThickness)
    OutlineColorRegistry.registerItemThickness(
        ResourceLocation.fromNamespaceAndPath("mymod", "big_axe"), 
        3
    );

    // Disable outline for specific item
    OutlineColorRegistry.disableItem(
        ResourceLocation.fromNamespaceAndPath("mymod", "special_item")
    );
});
```

---

### 7. ⚡ Pure Client-Side — Plug and Play

- 🚫 **Zero server-side code**
- 🚫 **Zero network packets**
- ✅ **Join any multiplayer server**
- ✅ **Singleplayer / Multiplayer / Realm compatible**

---

### 8. 🧩 Seamless Compatibility Ecosystem

| Compatibility Type | Status | Notes |
|--------------------|--------|-------|
| Custom Model Mods (OptiFine / EMF / ETF) | ✅ Fully Compatible | Shader-level processing, no texture dependency |
| UI Beautification Mods | ✅ Compatible | Blur background disabled to avoid conflicts |
| Other Enchantment Visual Mods | ⚠️ Test Recommended | May produce overlay effects |
| Shader Packs | ✅ Compatible | Rendered before post-processing |


## ⚠️ Known Limitations

| Limitation | Cause | Mitigation |
|------------|-------|------------|
| 🔱 Thrown trident cannot color by enchantment | Client cannot access `pickupItemStack` enchantment list | Falls back to `isFoil()` check, uses item fixed color / default color |
| 🎣 Fishing rods, 🗺️ maps not outlined | BEWLR placeholder models lack geometric shapes | No current solution, does not affect normal use |
| 🛡️ Shields / 🔱 Tridents / 🔭 Spyglasses have approximate outlines | Box model approximation used | Still visually distinguishable |

---

## 🤝 Contributing & Feedback

- 🐛 **Bug Reports**: Please include Minecraft version, NeoForge version, and relevant mod list
- 💡 **Feature Suggestions**: New default enchantment color palettes or rendering contexts welcome
- 🔗 **Compatibility Issues**: Please provide conflicting mod name and version

---

<div align="center">

**Made with 💜 and a lot of shader magic.**

*Every enchanted item deserves to be seen.*

**轮廓即宣言 · Enchanted Outlines**

</div>
