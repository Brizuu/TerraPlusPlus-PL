# TerraPlusMinus-PL 🌍

**TerraPlusMinus-PL** is a highly optimized fork of the TerraPlusMinus project, specifically tuned for the **BuildTheEarth** project. This plugin bridges OpenStreetMap (OSM) data and real-world elevation into Minecraft with a focus on stability, high performance, and error-free terrain generation.

---

## 🚀 Key Enhancements

This version includes critical fixes to prevent server crashes and IP blocking:

* **Async Chunk Repair:** Automatically detects failed chunk loads and schedules a background refresh without freezing the main server thread.
* **Active Request Pacing:** Implements a request-throttling system for API calls. It prevents `HTTP 429 (Too Many Requests)` errors by staggering outgoing connections, especially during heavy operations like `//regen`.
* **Intelligent Caching:** Uses `SoftValues` caching to optimize RAM usage. The server will automatically clear old terrain data if it needs more memory for players, preventing `OutOfMemory` crashes.
* **Safety Barriers:** Integrated with a `PlayerMoveListener` to prevent players from entering ungenerated "void" areas until the data is successfully fetched.

---

## 🛠 Dependencies

This project relies on the following core libraries to handle geographic projections and data fetching. Ensure these are available in your development environment:

* **[TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus)** - The base implementation for modern BTE terrain generation.
* **[TerraMinusMinus](https://github.com/BuildTheEarth/terraminusminus)** - The core library for geographic data processing and coordinate projection.

---
