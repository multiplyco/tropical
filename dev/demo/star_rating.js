// <star-rating average="…" votes="…" mine="…" counts="[…]">: the demo's
// ratings, as a Rocket component. Rocket comes with Datastar in
// datastar-rocket.js, which the page loads in place of datastar.js, and names
// `datastar` in its import map, so that this module imports the page's own copy.
//
// The island renders the props, from every user's ratings, and Rocket renders
// again when a patch changes them. What only the browser changes, the star
// under the pointer and whether the breakdown is open, is in the component's
// local signals, which a patch leaves alone. A click is a `rate` event, the
// stars in its detail, for the island's action. It draws into a shadow root,
// Rocket's default, which the island's morph doesn't reach; the label is the
// island's, in the light DOM, shown through the slot.
import {rocket} from 'datastar';

const stars = [1, 2, 3, 4, 5];

rocket('star-rating', {
  props: ({number, array}) => ({
    average: number.clamp(0, 5),
    votes: number.min(0),
    mine: number.clamp(0, 5),
    counts: array(number),
  }),

  setup: ({$$, action, emit}) => {
    $$.hover = 0;
    $$.open = false;
    action('rate', (_, n) => emit('rate', {stars: n}));
  },

  render: ({html, props: {average, votes, mine, counts}}) => html`
    <style>
      :host { display: block; }
      .row { display: flex; align-items: center; gap: .75rem; }
      .stars { display: flex; font-size: 1.75rem; line-height: 1; }
      .stars button { all: unset; cursor: pointer; padding: 0 .1rem; color: var(--star-off, #a1a1aa); }
      .stars button.lit { color: var(--star-on, #f59e0b); }
      .summary { font-size: .875rem; opacity: .7; }
      .toggle { all: unset; cursor: pointer; font-size: .875rem; color: var(--link, #4f46e5); }
      ul { list-style: none; margin: .5rem 0 0; padding: 0; font-size: .75rem; }
      li { display: flex; align-items: center; gap: .5rem; }
      .bar { height: .5rem; border-radius: .25rem; background: var(--star-on, #f59e0b); }
    </style>
    <slot></slot>
    <div class="row">
      <div class="stars" data-on:mouseleave="$$hover = 0">
        ${stars.map((n) => html`
          <button type="button" aria-label="${n} of 5" data-class:lit="($$hover || ${mine}) >= ${n}"
                  data-on:mouseenter="$$hover = ${n}" data-on:click="@rate(${n})">★</button>`)}
      </div>
      <span class="summary">${votes ? `${average.toFixed(1)} from ${votes}` : 'No ratings yet'}</span>
      <button type="button" class="toggle" data-on:click="$$open = !$$open"
              data-text="$$open ? 'Hide the breakdown' : 'Breakdown'">Breakdown</button>
    </div>
    <ul data-show="$$open">
      ${stars.toReversed().map((n) => html`
        <li><span>${n} ★</span><span class="bar" style="width: ${votes ? (6 * (counts[n - 1] ?? 0)) / votes : 0}rem"></span>
          <span>${counts[n - 1] ?? 0}</span></li>`)}
    </ul>
  `,
});
