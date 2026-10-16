# 2026-10-10 — A formatter dropped imports in use

IntelliJ's formatter, run over the tree, removed imports still in use: `BrotliEncoder` and `ZstdEncoder` from
`compression.clj`, and `ZstdInputStreamNoFinalizer` from `ring_test.clj`. Each class was used only as a constructor,
`(ZstdEncoder. ...)`, or through a method value, `ZstdEncoder/.nativeSize`, which it took for no use. Nothing that
loads `compression` compiled, so the first test namespace failed to load, and `bb lint` reported each as an unresolved
symbol. After formatting, `bb lint` and `bb test` find it.

The tree after these, `bb test` and `bb test:linux all`, on amd64 and arm64, with glibc and with musl: 145 tests, 782
assertions, no failures.
