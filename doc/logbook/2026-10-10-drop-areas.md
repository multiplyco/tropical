# 2026-10-10 — Drop areas

Item 24 of the app's requirements: `upload/drop-area`, `upload/dropping`, and `upload/drop-guard` in
`ui/page-attrs`. In the demo, the root island is a drop area with a list of its own, holding the uploads card's.

In Chrome, synthetic drag events, each a `DragEvent` with a `DataTransfer` holding a `File`:

- On the page outside the card, a drag of files showed the page's overlay alone, and over the card, the card's alone.
  Leaving the page's element for the card's didn't clear it; leaving the window, `relatedTarget` null, did.
- A drag of text over the card wasn't taken: not prevented, the overlay as it was.
- A drop on the card uploaded its file into the card's list; one on the page outside it, into the page's list, and
  not the card's.
- On a topic page, with no areas, the body refused a drag and a drop of files, and let a drag of text be. A file
  input added to the page took files, not refused.
- Two areas side by side, added to that page: each showed its own overlay; over the gap between them, neither did, and
  a drop there was refused and reached neither. A drop on one reached that one alone.

Not shown by synthetic events: the drop effect, which a constructed `DataTransfer` keeps at `none`, and the browser
opening a file dropped where nothing takes it. By hand, screenshots dragged from the Finder into Chrome on macOS: one
dropped on the page outside the card went to the page's list, one dropped on the card to the card's, and both overlays
were cleared after. On a topic page, with no areas, a drop left the tab on its page. No cursor showed the refusal there:
macOS has none for a drop refused, only a badge on one taken.

`bb test`: 129 tests, 727 assertions.
