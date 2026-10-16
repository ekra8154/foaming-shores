# Foaming Shores

A client-side Fabric mod for Minecraft 26.3 (Mojang mappings). Procedurally animated
pixel-art waves wash up from oceans, lakes and rivers onto the shore, then recede and
dissolve, drawn to blend in with vanilla water. Lone water sources never get waves.

The shore is heard as well as seen: waves breaking, the run-up and backwash, foam fizzing,
slaps against walls, water pouring over drops and the distant surf, all synthesised live from
the same wave timing as the picture (bubble populations after van den Doel's liquid sound model,
filtered noise and a small reverb). Nothing is recorded. It plays under Ambient/Environment.

## Building

```
gradlew build
```

The shippable jar is `build/libs/foaming-shores-<version>.jar` (26.x is mojmap-native, so
there is no remap step).

## Development

```
gradlew runClient
```
