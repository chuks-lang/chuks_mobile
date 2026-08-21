# Chuks Mobile — Component Reference

Everything is styled through **semantic theme tokens**, so components reskin with
`setTheme("light")` and stay consistent. You compose with `tw()` utility classes.

## Theme tokens — `core/theme.chuks`

Color *roles* (not shades), each defined for light and dark:

`bg` `surface` `surface2` `elevated` · `text` `muted` `subtle` `inverse` ·
`primary` `primaryText` `primarySoft` `accent` · `border` `borderSoft` ·
`success`/`successText`/`successSoft` · `warning`/… · `danger`/… · `info`/`infoSoft` · `overlay`

```chuks
import { ThemeMode, setTheme, toggleTheme, currentTheme, tk } from "../core/theme.chuks";
setTheme(ThemeMode.Light)          // type-safe enum (Light | Dark), not a string
toggleTheme()                      // flip; re-render to apply
const c: string = tk("primary")    // resolve a role to hex for the active mode
```

Spacing scale (`tkSpace`): `xs`=4 `sm`=8 `md`=12 `lg`=16 `xl`=24 `2xl`=32 `3xl`=48.

## Utility classes — `tw()` — `core/tw.chuks`

`tw("bg-surface text-muted p-md gap-sm rounded-lg items-center justify-between")`

- **Color:** `bg-<role|palette|hex>`, `text-<role|palette|hex>` (roles resolve to the active theme)
- **Spacing:** `p-<n|token>` (`p-4`=16, `p-md`=12), `gap-<n|token>`
- **Sizing:** `w-<n>`, `h-<n>` (×4px)
- **Layout:** `flex-row` `flex-col` `flex-wrap` `grow` `items-*` `justify-*`
- **Radius:** `rounded` `rounded-sm|md|lg|xl|2xl|3xl|full`
- **Border:** `border` `border-2` `border-<color>`
- **Type:** `text-xs|sm|base|lg|xl|2xl|3xl|4xl`, `font-normal|semibold|bold`, `text-left|center|right`
- **Effects:** `shadow-sm|md|lg`, `opacity-<0-100>`
- **Position:** `absolute` `top-<n>` `left-<n>` `right-<n>`

## Content kit — `core/kit.chuks`

Each component takes a trailing `extra` tw-string that wins.

| Component | Signature |
|---|---|
| Button | `Button(variant, label, onPress, extra)` — variant: `primary`/`secondary`/`ghost`/`danger` |
| Card | `Card(extra, kids)` |
| Input | `Input(placeholder, onChange, extra)` |
| Badge | `Badge(tone, text)` — tone: `neutral`/`primary`/`success`/`warning`/`danger`/`info` |
| Chip | `Chip(text, selected, onPress)` |
| Avatar | `Avatar(initials, extra)` |
| Divider | `Divider()` |
| SectionHeader | `SectionHeader(text)` |
| ListItem | `ListItem(title, subtitle, trailing, onPress)` |
| Switch | `new Switch(on)` — class; `.view()`, `.onToggle` |

```chuks
import { Button, Card, Badge } from "../core/kit.chuks";
Card("", [
    Text(tw("text-base font-bold text-text"), "Title"),
    Button("primary", "Save", function(): void { self.save() }, "grow"),
])
```

## Structural / navigation — `core/nav.chuks`

**Core is icon-agnostic** (the React Native model, not Flutter's bundled set): it
never imports an icon package. You supply icons — a prebuilt node for the bar
actions, or a render function for the tabs. `@chuks/lucide` is the recommended
icon set (like `@expo/vector-icons`), imported **app-side** via `utils/icons.chuks`.

| Component | Signature | Status |
|---|---|---|
| NavBar | `NavBar(title, leading, onLeading, trailing, onTrailing)` — `leading`/`trailing` are icon nodes or null | done |
| TabBar | `TabBar(names, labels, iconFor, active, onSelect)` — `iconFor`: `(name, color, size) -> Node` | done |
| BottomSheet | — | todo |
| Modal | — | todo |
| Toast | — | todo |

```chuks
import { lucide } from "../utils/icons.chuks";   // app-side; core never imports this
NavBar("Inbox", lucide("chevron-left", 22, tk("text")), onBack, null, null)
TabBar(["house","bell"], ["Home","Alerts"],
       function(n,c,s){ return lucide(n, s, c) }, active, onSelect)
```
