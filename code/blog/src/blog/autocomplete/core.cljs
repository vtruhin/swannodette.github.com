(ns blog.autocomplete.core
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]]
    [cljs.core.match.macros :refer [match]])
  (:require
    [cljs.core.match]
    [cljs.core.async :refer [>! <! alts! put! sliding-buffer chan]]
    [blog.responsive.core :as resp]
    [blog.utils.dom :as dom]
    [blog.utils.reactive :as r]))

(def base-url
  "http://en.wikipedia.org/w/api.php?action=opensearch&format=json&search=")

;; -----------------------------------------------------------------------------
;; Interface representation protocols

(defprotocol IHideable
  (-hide! [view])
  (-show! [view]))

(defprotocol ITextField
  (-set-text! [field txt])
  (-text [field]))

(defprotocol IUIList
  (-set-items! [list items]))

;; =============================================================================
;; Autocompleter

(defn menu-proc [select cancel input menu data]
  (let [{sel :chan ctrl :control} (resp/selector select menu data)]
    (go
      (let [[v sc] (alts! [sel cancel])]
        (if (= sc cancel)
          ::cancel
          (do (-set-text! input v)
            (>! ctrl :exit)
            (-hide! menu)
            v))))))

(defn show-results [menu items]
  (->> (for [item items] (str "<li>" items "</li>"))
    (apply str)
    (-set-items! menu)))

(defn autocompleter* [fetch select cancel input menu]
  (let [out (chan)]
    (go (loop [items nil]
          (let [[v sc] (alts! [cancel select fetch])]
            (cond
              (= sc cancel)
              (do (-hide! menu)
                (recur items))

              (and items (= sc select))
              (let [v (<! (menu-proc (r/concat [v] select)
                            cancel input menu items))]
                (if (= v ::cancel)
                  (recur nil)
                  (do (>! out v)
                    (recur items)))))

              (= sc fetch)
              (let [[v c] (alts! [cancel (r/jsonp (str base-url v))])]
                (if (= c cancel)
                  (do (-hide! menu)
                    (recur nil))
                  (do (-show! menu)
                    (let [items (nth v 1)]
                      (show-results menu items)
                      (recur items)))))

              :else
              (recur items))))
    out))

;; =============================================================================
;; HTML Specific Code

(extend-type js/HTMLInputElement
  ITextField
  (-set-text! [field text]
    (set! (.-value list) text))
  (-text [field]
    (.-value field)))

(extend-type js/HTMLUListElement
  IHideable
  (-hide! [list]
    (dom/add-class! list "hidden"))
  (-show! [list]
    (dom/remove-class! list "hidden")))

(defn html-menu-events [input menu]
  (r/fan-in
    [(->> (r/listen input :keyup)
       (r/map resp/key-event->keycode)
       (r/filter resp/KEYS)
       (r/map resp/key->keyword))
     (r/hover-child menu "li")
     (r/map (constantly :select)
       (r/listen menu :click))]))

(defn html-input-events [input]
  (->> (r/listen input :keyup)
    (r/map #(-text input))
    (r/split #(string/blank? %))))

(defn html-autocompleter [input menu msecs]
  (let [[filtered removed] (html-input-events input)
        ac (autocompleter*
             (r/throttle filtered msecs)
             (html-menu-events input menu)
             (r/map (constantly :cancel)
               (r/fan-in [removed (r/listen input :blur)]))
             input menu)]
    ac))

;; =============================================================================
;; Example

(let [ac (html-autocompleter
           (dom/by-id "autocomplete")
           (dom/by-id "autocomplete-menu")
           750)]
  (go (while true (<! ac))))
