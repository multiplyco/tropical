# Components with Rocket

Rocket is Datastar's way of making web components, and it comes with Datastar in a bundle of its own. To Tropical, a
Rocket component is a script the islands declare ([Scripts the page needs](scripts.md)) and an element like any
other: the island renders its props as attributes and takes its events as actions. Nothing in Tropical is Rocket's
own; this guide is how the two meet. Rocket is in beta, and the guide follows the demo's `<star-rating>`
(`dev/demo/star_rating.js`), against Datastar 1.0.4 with Rocket beta.2.

The page loads the bundle with Rocket in place of `datastar.js`, and names it in an import map:

```clojure
(def datastar-url
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar-rocket.js")

[:head
 ;; Before the first module.
 [:script {:type "importmap" :nonce nonce} (h/raw (ui/json {:imports {:datastar datastar-url}}))]
 [:script {:type "module" :nonce nonce :src datastar-url}]
 (ring/scripts nonce)]
```

A component imports Rocket from it:

```js
// app/star_rating.js
import {rocket} from 'datastar';

rocket('star-rating', {
  props: ({number}) => ({average: number.clamp(0, 5), mine: number.clamp(0, 5)}),
  setup: ({$$, action, emit}) => {
    $$.hover = 0;
    action('rate', (_, n) => emit('rate', {stars: n}));
  },
  render: ({html, props: {average, mine}}) => html`
    <slot></slot>
    ${[1, 2, 3, 4, 5].map((n) => html`
      <button data-class:lit="($$hover || ${mine}) >= ${n}" data-on:mouseenter="$$hover = ${n}"
              data-on:mouseleave="$$hover = 0" data-on:click="@rate(${n})">★</button>`)}
    <span>${average.toFixed(1)}</span>`,
});
```

And an island uses it:

```clojure
(script/defscript star-rating
  "`<star-rating>`, a Rocket component."
  {:resource "app/star_rating.js"})

(defisland rating
  [uid]
  (let [ratings (use-watch !ratings)
        rate    (use-action :rate
                  (fn [_ {stars "stars"}]
                    ;; Untrusted: a whole number of stars, from 1 to 5, or nothing.
                    (when (contains? #{1 2 3 4 5} stars)
                      (swap! !ratings assoc uid stars))
                    nil)
                  {:value "evt.detail"})]
    (script/use star-rating)
    [:star-rating {:average (average ratings) :mine (get ratings uid 0) :data-on:rate rate}
     [:span "Rate this."]]))
```

- **One Datastar.** The bundle with Rocket holds Datastar, so it replaces `datastar.js`, and a component imports
  `rocket` from the page's copy. A second copy would be a second engine. In CSP mode, it would compile nothing either,
  since Datastar takes the page's nonce off `<html>` as it starts. The import map names the copy once, so the page's
  tag and the modules can't disagree on its URL. It goes before the first module, with the nonce.
- **Loaded like any script.** `defscript` and `script/use` load the component's module: in the head with the first
  frame, or over the stream when an island using it is patched in, once per page. Rocket defines the element as the
  module runs, so an edited component shows on the next page load, as with any `customElements.define`.
- **Props are the island's.** The island renders them as attributes, a structured one as JSON, which `ui/json`
  writes. Rocket decodes them with the component's codecs, and renders again when a patch changes them. A prop the
  component sets itself is written back to its attribute, and the island's next render puts its own value back, or,
  if it renders none, removes it, and the prop falls back to its default. What the component owns goes in its local
  signals; or the island renders `data-preserve-attr` naming the attribute, and the morph leaves it.
- **Events are actions.** The component emits an event from its element with `emit`. The island takes it with
  `data-on:<event>` on the element, and the event's detail with `use-action`'s `{:value "evt.detail"}`, untrusted, as
  any value from the browser ([Actions](actions.md)). The element's own attributes are in the page's scope, not the
  component's.
- **Local signals stay with the element.** `$$name` is the component's own signal, under `_rocket.<tag>.<id>`. Its
  key starts with `_`, so no action or stream sends it, and a patch leaves it. It lasts as long as the element stays
  where it is: one that is moved, as the morph moves an element when its list reorders, or replaced, starts over, as
  Rocket drops its signals when it leaves the page. What must outlast that is the island's (`use-state`) or the
  browser's ([State the browser owns](browser-state.md)).
- **A shadow root, not `light`.** The default mode, `open`, and `closed` draw into a shadow root, which the island's
  morph doesn't reach. `light` draws into the element's children, which the morph matches to the island's. A frame
  that reaches the element without changing its props, such as the resync after a reconnect, removes what the
  component drew. One that changes them is undone the other way: the component renders again with the children it
  took in when it started, in place of those the island just sent. The page's classes don't reach into a shadow root,
  so style the component in its render, and theme it with CSS custom properties, which do.
- **The island's children are slotted.** What the island renders in the element is in the page's DOM, styled by the
  page, shown through the component's `<slot>`, and patched as the rest of the island. Rocket rewrites the
  expressions in it for the component: `$$name` is the component's signal, and an `@action(...)` goes to the
  component first, which passes those it doesn't define to Datastar. An island's actions work there, as `@post`
  isn't the component's, but a component action named as one of Datastar's would take its calls.
- **The `js` codec is code.** It makes functions of what an attribute holds, so a server value in such a prop is the
  injection [Security](security.md) describes for expressions. Values go through the others, such as `json` and
  `string`.
- **Untested:** a component inside another's children. Rocket tells their children apart by a marker on each
  element, `data-rocket-host`, which the morph removes, as any attribute the island didn't render. A component's own
  children, the island's actions among them, don't need it.
