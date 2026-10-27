# Foaming Shores

A client-side Fabric mod for Minecraft 26.3. Pixel-art waves wash up from the sea onto the shore,
then recede and dissolve, drawn to blend in with vanilla water. Rivers flow, lakes ripple in the
wind, and the shore is heard as well as seen. Lone water sources never get waves.

## What it draws

- **Beaches:** waves come in from open water, run up the sand as a thin sheet of water with a foam
  lip, leave lace behind and slide back, and the sand stays dark and wet for a while. Fronts keep
  their shape all the way in, cross where they meet behind an island, and calm down in coves.
- **Walls and drops:** water washes up walls and piles foam at their foot, and pours over ledges
  and drops.
- **Rivers:** flow lines carried by the current, foam piling against rocks with wakes and trails
  behind them, lighter shallows and wet banks. Each river keeps the way it runs between visits.
- **Lakes and still water:** ripples from a wandering wind, lapping on the shore the wind blows
  onto, rain rings, and now and then a fish rising or a bug touching the water.
- **Sound:** breaking waves, run-up and backwash, fizzing foam, slaps against walls and water
  pouring over drops, all synthesised live from the same wave timing as the picture (bubble
  populations after van den Doel's liquid sound model, filtered noise and a small reverb). Nothing
  is recorded. It plays under Ambient/Environment.

## Using it

- **Requirements:** Fabric Loader 0.19.5 or newer, Fabric API, Java 25.
- **Settings:** with Mod Menu and Cloth Config installed, open the mod's settings from the mod list.
  Without them, edit `config/foamingshores.json`.
- **Detail:** Light (only near you, cheapest), Balanced (the default) or Full (foam out to about
  190 blocks, as sharp as the screen can show).
- **On and off:** F9 turns the whole mod on or off (rebind it under Controls). Off, it computes
  nothing at all.
- **Client-side only:** it works on any server, and nothing is sent to it.
- **Shaderpacks:** works with Iris (tested with Complementary).

## Building

```
gradlew build
```

The jar to ship is `build/libs/foaming-shores-fabric-<Minecraft versions>-v<version>.jar`
(26.x is mojmap-native, so there is no remap step).

## Development

```
gradlew runClient
```

## License

MIT, see [LICENSE](LICENSE).
