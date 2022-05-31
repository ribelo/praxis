(ns test
  (:require [ribelo.praxis :as p]))

(p/defeffect :id [bar baz] (+ bar baz))
(p/defevent :id [bar baz] (+ bar baz))
