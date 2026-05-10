# Principles

Five ideas this template is built on. Most of the design choices in the rest of these docs are consequences of one of them.

## Domain-first

The domain describes what the business does. It does not describe how data moves over the wire, how it's stored, or what serializer is in fashion this year. Domain types live in their own package and depend on nothing infrastructure-shaped.

The practical consequence: when you need a JSON encoder or a SQL codec, write it next to the consumer, not next to the domain type. Routes know about JSON. Repositories know about rows. The domain doesn't have to learn either.

The benefit shows up when you change something. Swap one codec library for another, replace your database driver, restructure your wire protocol — the domain doesn't move. The surface around it does.

## Type safety as a feature, not a tax

Primitives don't know what they mean. A function taking three `Int`s is impossible to call correctly without staring at the signature. Wrap them — one opaque type per concept, with validation at construction — and the compiler refuses to mix them up.

Apply the same treatment to state. An ADT with one case per situation beats a status string every time, because every call site has to handle every case explicitly. The compiler walks you through the consequences of adding a new state.

The cost is a little ceremony at the boundaries: parsing a request body produces typed values, persisting a row produces typed values, and so on. Inside the system there are no strings pretending to be ids and no integers pretending to be dimensions. Bugs that would have been runtime failures become compile errors instead.

## Composition over duplication

If the same shape appears twice, lift it. Module traits compose features. Provider mixins let each module declare what it contributes — routes, tasks, mail previews — without any module having to know about the others. Runtime types swap implementations without touching call sites.

The price is one extra level of indirection. The payoff is that adding a new module, a new background task, or a new storage backend doesn't require remembering what already exists.

A corollary: stay one level of abstraction away from "framework." Each building block should be small enough that you can read it end to end and decide whether you want it. Nothing here is magic; everything is a Scala class or trait you can `cmd-click` into.

## Functional and explicit

Immutable data. Pure functions that take and return state. Pattern matching over conditionals. ADTs to express outcomes, including the unhappy ones.

Errors the caller is supposed to handle are values returned from the function, not exceptions thrown across the stack. Truly exceptional conditions — connection drops, JVM out-of-memory, a bug — surface as IO failures and are logged, but business outcomes don't.

The result: failure modes you can read in a type signature, and control flow you can follow without grepping for `try`. When a method returns `IO[XResult]`, every reachable case appears in the enum and the route handler matches them all.

## Pluggable and pragmatic

This is a template, not a framework. Delete the parts you don't need. Swap the ones that don't fit. The runtime types — cache, storage, event bus, rate limiter — exist so you can replace them without rewriting the call sites. The wine-auction domain is a worked example to demonstrate the conventions, not a foundation; if you don't want auctions, the rest of the template still runs.

Pragmatism beats purity where users can tell the difference. Soft delete instead of hard delete, because operators want recoverable mistakes. Cleanup-on-failure instead of two-phase commit, because the simpler pattern is correct enough and easier to test. Two database round-trips instead of one when one is awkward to express. The point is not theoretical elegance; the point is code that you can read in a year and still understand.
