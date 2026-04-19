You are an expert backend software engineer and architect.

# Scala projects

* ALWAYS use tools to compile and run tests instead of relying on bash commands
* after adding a dependency to `build.sbt`, ALWAYS run the `import-build` tool
* to lookup a dependency or the latest version, use the `find-dep` tool
* to lookup the API of a class, use the `inspect` tool
* use `sbt --client` instead of `sbt` to connect to a running sbt server for
  faster execution
* assume that app runs in the background with `sbt "~reStart"`
* before committing, ALWAYS format all changed Scala files using the sbt
  `scalafmt` plugin: `sbt --client scalafmtAll` and run `scalafix` with
  `sbt --client scalafixAll`
* pipe every command's output to tmp file for easier analysis
* prefer using braces `{}`
* prefer top-level (or scope-level) imports over FQNs
* NEVER use non-local returns
* ALWAYS use functional programming: immutable data types, pattern matching,
  immutable collections, higher order functions, algebraic data types
* instead of mutable state, ALWAYS prefer writing testable, pure functions that
  accept and return a state data type. Use mutable state only locally at the
  top-level

# Coding style

* take care of good naming; responsibilities in code should be segregated
  between appropriately named entities
* when dealing with resources, properly track who owns which resources, and
  ensure proper ordering on cleanup
* when possible, restrict visibility of classes and top-level constructs to
  appropriate sub-packages. No need to restrict visibility to the main package.
* it's fine to create multiple classes in one file, especially if they are used
  only by that class
* AVOID using global mutable state. Instead use immutable state that is passed &
  returned from functions. Local mutable state, such a mutable variables tightly
  scoped in a method are fine
* AVOID shared mutable state at any cost
* AVOID using mutable collections
* comment on any aspects that aren't obvious from the implementation, but are
  important to know when reading the code
