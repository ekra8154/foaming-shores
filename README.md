# Foaming Shores

Pixel-art waves wash up from the sea onto the shore, then recede and dissolve, drawn to blend in
with vanilla water. Rivers flow, lakes ripple in the wind, and the shore is heard as well as seen, all client side. 

## Beaches

Waves come in from open water and run up the sand as a thin sheet of water with a foam lip. They
leave lace behind as they slide back, and the sand stays dark and wet for a while after. Fronts
keep their shape all the way in, cross where they meet behind an island, and calm down in sheltered
coves. Water washes up walls and piles foam at their foot, and pours over ledges and drops. Lone
water sources never get waves.

## Rivers and lakes

Rivers carry flow lines with the current, pile foam against rocks with wakes and trails behind
them, and have lighter shallows and wet banks. Each river keeps the way it runs between visits.

Lakes and still water ripple in a wandering wind and lap at the shore it blows onto. Rain makes
rings, and now and then a fish rises or a bug touches the water.

## Sound

Breaking waves, run-up and backwash, fizzing foam, slaps against walls and water pouring over
drops, all synthesised live from the same wave timing as the picture (bubble populations after van
den Doel's liquid sound model, filtered noise and a small reverb). Nothing is recorded. It plays
under Ambient/Environment and can be turned off in the settings.

## Settings and performance

- **Detail:** Light (only near you, cheapest), Balanced (the default) or Full (foam out to about
  190 blocks, as sharp as the screen can show).
- **F9** turns the whole mod on or off, to compare (rebind it under Controls). Off, it computes
  nothing at all.
- Settings open from Mod Menu (with Cloth Config installed), or edit `config/foamingshores.json`.

Foam further away is drawn coarser, only where the screen could not show the difference, and new
areas fade in rather than popping.

## Compatibility

- Requires Fabric Loader 0.19.5 or newer, Fabric API and Java 25.
- Client-side only: works on any server, nothing is sent to it.
- Works with Sodium and with Iris shaderpacks (tested with Complementary).


## License

MIT, see [LICENSE](https://github.com/ekra8154/foaming-shores/blob/main/LICENSE).
