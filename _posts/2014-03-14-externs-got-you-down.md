---
layout: post
title: "Externs Got You Down?"
description: ""
category: 
tags: []
---
{% include JB/setup %}

**Update**: This post was written nearly a year ago. A lot of things
can change in a year. People start using React and third party
JavaScript packaging stories for ClojureScript can appear out of
nowhere. This post describes a quick hack when you are in a bind but I
would not use it in production setting. Use a
[CLJSJS](http://cljsjs.github.io) package instead.

While ClojureScript's strategy of generating and leveraging code
optimized for the [Google Closure Compiler](http://developers.google.com/closure/compiler/) has turned out to be fantastic
for managing production code size, sometimes you find yourself needing a
great JavaScript library that simply does not play well with advanced
compilation. Integrating these libraries is problematic because of the
[externs file requirement](http://developers.google.com/closure/compiler/docs/api-tutorial3). For
example this is true for
[React](http://facebook.github.io/react/). Fortunately someone has
taken the time to write the externs file for React, but most of the
time you won't be so lucky.

For some JavaScript libraries there is an acceptable workaround - you
can use the JavaScript library itself as an externs file! This would
normally spew many warnings from Closure Compiler, but recent
versions of ClojureScript expose knobs to control how Closure
reports warnings.

For example imagine that an externs file did not exist for
React. The following [lein-cljsbuild](http://github.com/emezeske/lein-cljsbuild) build specification is enough to
produce a proper advanced compiled production file:

```clj
{:id "release"
 :source-paths ["src"]
 :compiler {
   :output-to "main.js"
   :optimizations :advanced
   :preamble ["react/react.min.js"]
   :externs ["react/react.js"]
   :closure-warnings {:externs-validation :off
                      :non-standard-jsdoc :off}}}
```

That's it!
