# Global state: the store

Status: implemented (`app/store/store.chuks`), wired into Home + Alerts, verified
VM ≡ AOT and on the iOS simulator. Last updated: 2026-08-19.

## When to use the store vs useState

- **Local / ephemeral state** (a toggle, a tab index, a form draft): use `useState`.
  It belongs to one component and dies with it.
- **Shared state** (the signed-in user, a cart, unread counts, anything two screens
  must agree on, or state you keep between sessions): use the store.

Rule of thumb from React, which applies here too: keep it local, lift it to the
store only when more than one place needs it.

## The model: plain state + functions, no Redux ceremony

The store is just module state plus functions that change it. There are **no
actions, no reducer, no action creators, and no immutable rebuilds**. A change
function simply mutates the state, and the framework re-renders.

Why this is correct (and not a shortcut): Redux forces immutable reducers because
it detects change by comparing state references. Chuks does not work that way. The
reconciler diffs the rendered **view** (the Node tree), not state references, so
mutating state in place and letting the framework re-render produces the right UI
with far less code. In-place mutation is verified to persist under both the VM and
a native AOT binary.

This is the Zustand model, not the Redux one.

## The store, in one file

```chuks
// app/store/store.chuks
export dataType Alert { id: int; title: string; time: string; read: bool }

function seed(): []Alert {
    var a0: Alert = { "id": 0, "title": "api-gateway is down", "time": "2 min ago", "read": false }
    var a1: Alert = { "id": 1, "title": "cache latency high",  "time": "18 min ago", "read": false }
    return [a0, a1]
}

var user: string = "Ada"
var alerts: []Alert = seed()

// reads (call these in a screen's render)
export function getUser(): string { return user }
export function getAlerts(): []Alert { return alerts }
export function unread(): int {
    var n: int = 0
    for (var i: int = 0; i < alerts.length; i = i + 1) { if (!alerts[i].read) { n = n + 1 } }
    return n
}

// changes (call these from an onPress; the framework re-renders after)
export function markRead(id: int): void {
    for (var i: int = 0; i < alerts.length; i = i + 1) { if (alerts[i].id == id) { alerts[i].read = true } }
}
export function markAllRead(): void {
    for (var i: int = 0; i < alerts.length; i = i + 1) { alerts[i].read = true }
}
```

## Using it in a screen

Read in `render`, change in an `onPress`. No `dispatch`, no `Action`, no `getState().x`.

```chuks
// app/tabs/alerts.chuks
import { Alert, getAlerts, unread, markRead, markAllRead } from "../store/store.chuks";

function alertRow(al: Alert): Node {
    var dot: string = al.read ? "" : "●"
    return ListItem(al.title, al.time, dot, function(): void { markRead(al.id) })  // just call it
}

export function Alerts(): Node {
    var list: []Alert = getAlerts()
    var kids: []Node = [ Text(tw("text-sm text-muted"), string(unread()) + " unread") ]
    for (var i: int = 0; i < list.length; i = i + 1) { kids.push(alertRow(list[i])) }
    kids.push(Button("primary", "Mark all read", function(): void { markAllRead() }, ""))
    return Column(tw("bg-bg grow"), [ NavBar("Alerts", null, null, null, null),
                                      Column(tw("grow justify-start p-lg gap-sm"), kids) ])
}
```

Because the runtime re-renders the visible tree after every event, any screen
reading the store shows the new value the same frame. The **Home** tab reads the
same store for its greeting and unread count, so marking alerts read on **Alerts**
updates Home. No `Provider`, no `subscribe`, no selectors, no wiring between them.

## Derived values (selectors)

A "selector" is just a plain function of the state, kept in the store so screens do
not duplicate the derivation. `unread()` above is one. No memoization is needed:
the reconciler diffs the view, so recomputing every render is free.

## One store, or many

Redux is single-store by rule. Here it is your choice:

- **One store module** for the whole app (what we do now), or
- **Several store modules** split by domain (`session.chuks`, `cart.chuks`, ...),
  each a plain file like the one above, or
- **A generic `Store` class** for dynamic / many stores:
  `var cart = new Store(initial, reducer)`. Verified independent under VM + AOT.

They compose with zero ceremony: each store's change function runs inside an event,
the runtime re-renders, and each component reads whichever stores it needs.

## Side effects

Keep store change functions to state only (mutate, do not do I/O). Side effects
(fetch, timers, subscriptions) belong in `useEffect`, or in the event handler that
calls the store, which then mutates on completion. The result shows on the next
render.

## How it compares

| Model | Store count | Boilerplate | Re-render scoping |
| --- | --- | --- | --- |
| Redux | single (by rule) | actions + reducers + immutability | selectors + memo |
| Zustand / Jotai | many | low (set functions / atoms) | selector subscription |
| SwiftUI native | many `@Observable` | low | dependency tracking |
| TCA | one, scoped | high (State/Action/Reducer/Effect) | observation |
| **Chuks (ours)** | **one or many** | **lowest (mutate + re-render)** | **none needed (runtime diffs)** |
